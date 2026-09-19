package com.jarvis.core;

import com.jarvis.api.CancellationReceipt;
import com.jarvis.api.AppLaunchReceipt;
import com.jarvis.api.AppService;
import com.jarvis.api.CommandGateway;
import com.jarvis.api.CommandOutcome;
import com.jarvis.api.CommandRequest;
import com.jarvis.api.CommandResult;
import com.jarvis.api.CommandStatus;
import com.jarvis.api.CommandSubscription;
import com.jarvis.api.ConfirmationHandler;
import com.jarvis.api.CancellationToken;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.ContentSearchResult;
import com.jarvis.api.FileMatch;
import com.jarvis.api.FileMutationPreview;
import com.jarvis.api.FileMutationService;
import com.jarvis.api.FileOpener;
import com.jarvis.api.FileSearchQuery;
import com.jarvis.api.FileSearchResult;
import com.jarvis.api.FileSearchService;
import com.jarvis.api.SelectedFileResult;
import com.jarvis.api.HistoryEntry;
import com.jarvis.api.HistoryRepository;
import com.jarvis.api.HistoryResult;
import com.jarvis.api.MutationKind;
import com.jarvis.api.MutationReceipt;
import com.jarvis.api.OperationStatus;
import com.jarvis.api.PendingConfirmation;
import com.jarvis.api.ProgressEvent;
import com.jarvis.api.ProgressStage;
import com.jarvis.api.RiskLevel;
import com.jarvis.api.ServiceException;
import com.jarvis.api.StructuredError;
import com.jarvis.api.SystemInfoService;
import com.jarvis.api.SystemSnapshot;
import com.jarvis.api.UndoEntry;
import com.jarvis.api.UndoJournal;
import com.jarvis.api.UndoResult;
import com.jarvis.services.files.DocumentExtractionService;
import com.jarvis.services.files.ContentSearchService;
import com.jarvis.services.files.DocumentText;
import com.jarvis.services.files.ExtractionStatus;
import com.jarvis.services.files.FileService;
import com.jarvis.services.files.FileSystemFileService;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Executes parsed command plans against the real services.
 *
 * <p>Sprint 2 additions: per-session search-result context (the minimal
 * context engine — "move these files …" and "rename the newest …" resolve
 * against the most recent successful search of this session), a
 * preview-before-mutation confirmation flow ({@link ConfirmationHandler}),
 * undo of the latest reversible request through the {@link UndoJournal}, and
 * invalidation of cached results after any successful mutation or undo so
 * stale selections can never be replayed.</p>
 *
 * <p>When no confirmation handler is configured, risky operations are
 * rejected with {@link ErrorCode#CONFIRMATION_REQUIRED} and nothing is
 * executed — the gateway never mutates files without explicit approval.</p>
 */
public final class DefaultCommandGateway implements CommandGateway {
    private static final int HISTORY_LIMIT = 10;

    private final AppService appService;
    private final FileSearchService fileSearchService;
    private final SystemInfoService systemInfoService;
    private final HistoryRepository historyRepository;
    private final ExecutorService executor;
    private final CommandParser parser;
    private final FileMutationService mutationService;
    private final UndoJournal undoJournal;
    private final List<Path> scopeRoots;
    private final ConfirmationHandler confirmationHandler;
    private final FileService fileService;
    private final ContentSearchService contentSearchService;
    private final FileOpener fileOpener;
    private final SessionState sessionState = new SessionState();
    /** Guard for the bounded, ordered context/confirmation state machine. */
    private final Object stateLock = new Object();
    private PendingConfirmation pendingConfirmation;
    private List<PlannedOperation> pendingOperations;
    private PendingConfirmation lastPendingConfirmation;
    private final ConcurrentHashMap<UUID, Submission> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Sprint 1 composition: no file mutation, no confirmation flow. */
    public DefaultCommandGateway(
            AppService appService,
            FileSearchService fileSearchService,
            SystemInfoService systemInfoService,
            HistoryRepository historyRepository,
            ExecutorService executor) {
        this(appService, fileSearchService, systemInfoService, historyRepository, executor,
                null, null, List.of(), null, Clock.systemUTC());
    }

    /** Sprint 2 composition with mutation, undo, confirmation and a pinnable clock. */
    public DefaultCommandGateway(
            AppService appService,
            FileSearchService fileSearchService,
            SystemInfoService systemInfoService,
            HistoryRepository historyRepository,
            ExecutorService executor,
            FileMutationService mutationService,
            UndoJournal undoJournal,
            List<Path> scopeRoots,
            ConfirmationHandler confirmationHandler,
            Clock clock) {
        this(appService, fileSearchService, systemInfoService, historyRepository, executor,
                mutationService, undoJournal, scopeRoots, confirmationHandler, clock,
                new FileSystemFileService(), new ContentSearchService());
    }

    /** Sprint 4 composition: adds file-intelligence, document content search and file opening. */
    public DefaultCommandGateway(
            AppService appService,
            FileSearchService fileSearchService,
            SystemInfoService systemInfoService,
            HistoryRepository historyRepository,
            ExecutorService executor,
            FileMutationService mutationService,
            UndoJournal undoJournal,
            List<Path> scopeRoots,
            ConfirmationHandler confirmationHandler,
            Clock clock,
            FileService fileService,
            ContentSearchService contentSearchService) {
        this(appService, fileSearchService, systemInfoService, historyRepository, executor,
                mutationService, undoJournal, scopeRoots, confirmationHandler, clock,
                fileService, contentSearchService, (path, cancellation) -> {
                    throw new ServiceException(error(
                            ErrorCode.UNSUPPORTED_PLATFORM,
                            "Opening files is not configured in this build"));
                });
    }

    /** Sprint 4A composition: adds the contextual file-opener seam. */
    public DefaultCommandGateway(
            AppService appService,
            FileSearchService fileSearchService,
            SystemInfoService systemInfoService,
            HistoryRepository historyRepository,
            ExecutorService executor,
            FileMutationService mutationService,
            UndoJournal undoJournal,
            List<Path> scopeRoots,
            ConfirmationHandler confirmationHandler,
            Clock clock,
            FileService fileService,
            ContentSearchService contentSearchService,
            FileOpener fileOpener) {
        this.appService = java.util.Objects.requireNonNull(appService, "appService");
        this.fileSearchService = java.util.Objects.requireNonNull(fileSearchService, "fileSearchService");
        this.systemInfoService = java.util.Objects.requireNonNull(systemInfoService, "systemInfoService");
        this.historyRepository = java.util.Objects.requireNonNull(historyRepository, "historyRepository");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.parser = new CommandParser(java.util.Objects.requireNonNull(clock, "clock"));
        this.mutationService = mutationService;
        this.undoJournal = mutationService == null ? null : java.util.Objects.requireNonNull(undoJournal);
        this.scopeRoots = scopeRoots == null ? List.of() : List.copyOf(scopeRoots);
        this.confirmationHandler = confirmationHandler;
        this.fileService = java.util.Objects.requireNonNull(fileService, "fileService");
        this.contentSearchService = java.util.Objects.requireNonNull(contentSearchService, "contentSearchService");
        this.fileOpener = java.util.Objects.requireNonNull(fileOpener, "fileOpener");
        if (mutationService != null && scopeRoots.isEmpty()) {
            throw new IllegalArgumentException("mutation support requires at least one scope root");
        }
    }

    public SessionState sessionState() {
        return sessionState;
    }

    @Override
    public CommandSubscription submit(
            CommandRequest request,
            Consumer<ProgressEvent> onProgress,
            Consumer<CommandOutcome> onComplete) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(onProgress, "onProgress");
        java.util.Objects.requireNonNull(onComplete, "onComplete");
        Submission submission = new Submission(request.id());
        if (closed.get()) {
            safeAccept(onComplete, terminal(request.id(), CommandStatus.FAILED,
                    "JARVIS is shutting down", Optional.empty(), Optional.of(error(
                            ErrorCode.SERVICE_FAILURE, "Command gateway is closed")), Instant.now()));
            return submission;
        }
        if (active.putIfAbsent(request.id(), submission) != null) {
            safeAccept(onComplete, terminal(request.id(), CommandStatus.REJECTED,
                    "Duplicate request", Optional.empty(), Optional.of(error(
                            ErrorCode.INVALID_COMMAND, "Request ID is already active")), Instant.now()));
            return submission;
        }
        emit(onProgress, request.id(), ProgressStage.QUEUED, "Queued", 0, OptionalLong.empty());
        try {
            executor.execute(() -> execute(request, submission, onProgress, onComplete));
        } catch (RejectedExecutionException e) {
            active.remove(request.id());
            safeAccept(onComplete, terminal(request.id(), CommandStatus.FAILED,
                    "Command could not be scheduled", Optional.empty(), Optional.of(error(
                            ErrorCode.SERVICE_FAILURE, "Background executor rejected the command")), Instant.now()));
        }
        return submission;
    }

    private void execute(
            CommandRequest request,
            Submission submission,
            Consumer<ProgressEvent> onProgress,
            Consumer<CommandOutcome> onComplete) {
        Instant started = Instant.now();
        CommandOutcome outcome;
        try {
            cancelled(submission);
            emit(onProgress, request.id(), ProgressStage.PARSING, "Parsing command", 0, OptionalLong.empty());
            CommandPlan plan = parser.parse(request.originalText());
            cancelled(submission);
            emit(onProgress, request.id(), ProgressStage.PLANNING, "Planning command", 0, OptionalLong.empty());
            CommandResult result = executePlan(plan, submission, request.id(), onProgress);
            cancelled(submission);
            outcome = terminal(request.id(), CommandStatus.SUCCEEDED, summary(result),
                    Optional.of(result), Optional.empty(), started);
        } catch (CommandParseException e) {
            outcome = terminal(request.id(), CommandStatus.REJECTED, e.error().message(),
                    Optional.empty(), Optional.of(e.error()), started);
        } catch (ServiceException e) {
            CommandStatus status = switch (e.error().code()) {
                case INVALID_COMMAND, UNKNOWN_APP, TARGET_EXISTS, CONFIRMATION_REQUIRED, CONFIRMATION_DENIED ->
                        CommandStatus.REJECTED;
                case CANCELLED -> CommandStatus.CANCELLED;
                default -> CommandStatus.FAILED;
            };
            outcome = terminal(request.id(), status, e.error().message(),
                    Optional.empty(), Optional.of(e.error()), started);
        } catch (RuntimeException e) {
            StructuredError error = new StructuredError(ErrorCode.SERVICE_FAILURE,
                    "Unexpected command failure", Optional.ofNullable(e.getMessage()));
            outcome = terminal(request.id(), CommandStatus.FAILED, error.message(),
                    Optional.empty(), Optional.of(error), started);
        }

        emit(onProgress, request.id(), ProgressStage.PERSISTING, "Saving history", 0, OptionalLong.empty());
        outcome = persist(request, outcome);
        active.remove(request.id());
        safeAccept(onComplete, outcome);
    }

    private CommandResult executePlan(
            CommandPlan plan,
            Submission cancellation,
            UUID requestId,
            Consumer<ProgressEvent> onProgress) throws ServiceException {
        if (plan instanceof CommandPlan.OpenApp open) {
            return appService.launch(open.lookupName(), cancellation);
        }
        if (plan instanceof CommandPlan.FindFiles find) {
            emit(onProgress, requestId, ProgressStage.EXECUTING, "Scanning files", 0, OptionalLong.empty());
            FileSearchResult result = fileSearchService.search(find.query(), cancellation,
                    visited -> emit(onProgress, requestId, ProgressStage.EXECUTING,
                            "Scanning files", visited, OptionalLong.of(find.query().scanLimit())));
            sessionState.setSearchResult(result, find.query());
            return result;
        }
        if (plan instanceof CommandPlan.RefineSearch refine) {
            return executeRefinement(refine, cancellation, requestId, onProgress);
        }
        if (plan instanceof CommandPlan.SelectFile select) {
            return executeSelection(select, cancellation);
        }
        if (plan instanceof CommandPlan.OpenSelected) {
            return executeOpenSelected(cancellation);
        }
        if (plan instanceof CommandPlan.FileMutation mutation) {
            return executeMutation(mutation, cancellation, requestId, onProgress);
        }
        if (plan instanceof CommandPlan.ConfirmPending) {
            return executePendingMutation(requestId, onProgress);
        }
        if (plan instanceof CommandPlan.CancelPending) {
            return cancelPendingMutation();
        }
        if (plan instanceof CommandPlan.Undo) {
            return executeUndo(cancellation, requestId, onProgress);
        }
        if (plan instanceof CommandPlan.ListFiles) {
            FileSearchResult listed = listScopeFiles();
            // A listing acts like any other search: it seeds the session
            // context so "list files" + "move these files to X" composes.
            sessionState.setSearchResult(listed, null);
            return listed;
        }
        if (plan instanceof CommandPlan.FileInfo fileInfo) {
            return fileInfo(fileInfo.fileName());
        }
        if (plan instanceof CommandPlan.ContentSearch contentSearch) {
            return executeContentSearch(contentSearch.query(), cancellation, requestId, onProgress);
        }
        if (plan instanceof CommandPlan.SystemStatus) {
            return systemInfoService.snapshot(cancellation);
        }
        return new HistoryResult(historyRepository.recent(HISTORY_LIMIT, cancellation));
    }

    // ------------------------------------------------------------------
    // File mutations: plan -> confirm -> apply, with session invalidation.
    // ------------------------------------------------------------------

    private CommandResult executeMutation(
            CommandPlan.FileMutation plan,
            Submission cancellation,
            UUID requestId,
            Consumer<ProgressEvent> onProgress) throws ServiceException {
        requireMutationSupport();
        emit(onProgress, requestId, ProgressStage.AWAITING_CONFIRMATION,
                "Preparing confirmation preview", 0, OptionalLong.empty());
        // Each planner resolves concrete paths, builds the preview and asks
        // for confirmation before any filesystem effect happens.
        // Routing follows the operation kind; every selection (last result,
        // newest, oldest, explicit selection) is resolved inside the planners.
        List<PlannedOperation> operations = switch (plan.kind()) {
            case CREATE_FOLDER -> planCreateFolder(plan);
            case MOVE, COPY -> planTransfer(plan);
            case RENAME -> planRename(plan);
        };
        emit(onProgress, requestId, ProgressStage.EXECUTING, "Applying file operations",
                0, OptionalLong.empty());
        if (confirmationHandler == null) {
            // Typed follow-up flow (sprint 4A): with no handler seam the
            // exact preview and operations are stored; they run only on the
            // structured "confirm", never re-parsed or re-resolved.
            synchronized (stateLock) {
                pendingConfirmation = lastPendingConfirmation;
                pendingOperations = List.copyOf(operations);
            }
            return new FileMutationPreview(lastPendingConfirmation);
        }
        List<MutationReceipt.Entry> entries = new ArrayList<>();
        for (PlannedOperation operation : operations) {
            cancelled(cancellation);
            entries.add(operation.apply(requestId, mutationService));
        }
        // Cached search results no longer reflect the filesystem.
        sessionState.invalidateSearchResult();
        // A command whose every planned operation failed is a rejected
        // command, not a silent success: surface the first structured error.
        // Partial failures stay SUCCEEDED with per-entry truth in the receipt.
        boolean allFailed = entries.stream().allMatch(entry -> entry.status() == OperationStatus.FAILED);
        if (allFailed) {
            StructuredError first = entries.getFirst().error().orElseThrow(
                    () -> new IllegalStateException("failed entry without error"));
            throw new ServiceException(first);
        }
        return new MutationReceipt(toReceiptKind(plan.kind()), entries);
    }

    private void requireMutationSupport() throws ServiceException {
        if (mutationService == null) {
            throw new ServiceException(error(
                    ErrorCode.UNSUPPORTED_PLATFORM, "File mutations are not configured in this build"));
        }
    }

    // ------------------------------------------------------------------
    // File intelligence and document content search (sprint 4).
    // ------------------------------------------------------------------

    /**
     * Lists the regular files of every configured scope root (top level
     * only). The result reuses {@link FileSearchResult} so the context
     * engine and the UI file renderer treat it like any other search.
     */
    private FileSearchResult listScopeFiles() throws ServiceException {
        List<FileMatch> matches = new ArrayList<>();
        for (Path root : scopeRoots) {
            for (com.jarvis.services.files.FileInfo info : fileService.listFiles(root)) {
                matches.add(new FileMatch(info.absolutePath(), info.name(),
                        info.sizeBytes(), info.lastModified()));
            }
        }
        return new FileSearchResult(matches, matches.size(), false, false);
    }

    /** Metadata for one scope file, addressed by its file name only. */
    private FileSearchResult fileInfo(String fileName) throws ServiceException {
        Path file = requireScopeFile(fileName);
        com.jarvis.services.files.FileInfo info = fileService.getFileInfo(file);
        return new FileSearchResult(
                List.of(new FileMatch(info.absolutePath(), info.name(),
                        info.sizeBytes(), info.lastModified())),
                1, false, false);
    }

    /**
     * Resolves a user-supplied file name to exactly one file inside the
     * configured scope roots. Name matching is case-insensitive; ambiguity
     * or a missing file is a structured rejection, never a guess.
     */
    private Path requireScopeFile(String fileName) throws ServiceException {
        String wanted = fileName.toLowerCase(Locale.ROOT);
        List<Path> found = new ArrayList<>();
        for (Path root : scopeRoots) {
            for (com.jarvis.services.files.FileInfo info : fileService.listFiles(root)) {
                if (info.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                    found.add(info.absolutePath());
                }
            }
        }
        if (found.isEmpty()) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND, "No file named \"" + fileName + "\" in the configured scope"));
        }
        if (found.size() > 1) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND, "\"" + fileName + "\" matches " + found.size()
                            + " files; use a more specific name"));
        }
        return found.getFirst();
    }

    /**
     * Content search over the files of the most recent successful search of
     * this session. The service-level 100-document limit applies unchanged;
     * this method never widens it. Extraction failures are preserved per
     * document so they stay distinguishable from genuine zero matches.
     */
    private CommandResult executeContentSearch(
            String query,
            Submission cancellation,
            UUID requestId,
            Consumer<ProgressEvent> onProgress) throws ServiceException {
        if (scopeRoots.isEmpty()) {
            throw new ServiceException(error(
                    ErrorCode.UNSUPPORTED_PLATFORM, "Document search is not configured in this build"));
        }
        FileSearchResult source = requireLastResult();
        if (source.matches().isEmpty()) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND, "The previous search found no files to search inside"));
        }
        emit(onProgress, requestId, ProgressStage.EXECUTING, "Extracting document text",
                0, OptionalLong.of(source.matches().size()));
        ContentSearchService.SearchResult serviceResult =
                contentSearchService.searchContent(
                        source.matches().stream().map(FileMatch::path).toList(), query);
        List<ContentSearchResult.DocumentHit> documents = new ArrayList<>();
        for (ContentSearchService.DocumentResult document : serviceResult.getDocumentResults()) {
            if (document.extractionFailed()) {
                documents.add(ContentSearchResult.DocumentHit.failed(
                        document.path(), document.extractedText()));
            } else if (document.matchCount() > 0) {
                documents.add(ContentSearchResult.DocumentHit.matched(
                        document.path(), document.matchCount(), document.snippet()));
            } else {
                documents.add(ContentSearchResult.DocumentHit.noMatch(document.path()));
            }
        }
        int totalMatches = serviceResult.totalMatches();
        return new ContentSearchResult(query, documents, totalMatches, serviceResult.appliedLimits());
    }

    private List<PlannedOperation> planCreateFolder(CommandPlan.FileMutation plan) throws ServiceException {
        Path primaryRoot = scopeRoots.getFirst();
        Path target = primaryRoot.resolve(plan.name());
        PlannedOperation operation = PlannedOperation.createFolder(target);
        confirmSingle(operation, MutationKind.CREATE_FOLDER, RiskLevel.MEDIUM,
                "Create folder " + plan.name() + " in " + primaryRoot);
        return List.of(operation);
    }

    private List<PlannedOperation> planTransfer(CommandPlan.FileMutation plan) throws ServiceException {
        boolean move = plan.kind() == CommandPlan.FileMutation.Kind.MOVE;
        FileSearchResult source = requireLastResult();
        if (source.matches().isEmpty()) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND, "The previous search found no files to "
                    + (move ? "move" : "copy")));
        }
        FileSearchResult refined = switch (plan.selection()) {
            case LAST_RESULT, ALL_IN_SCOPE -> source;
            default -> resolveRefinementFiles(plan.selection(), source);
        };
        Path destinationFolder = resolveDestinationFolder(plan.name());
        boolean createDestination = !Files.isDirectory(destinationFolder, LinkOption.NOFOLLOW_LINKS);
        List<PlannedOperation> operations = new ArrayList<>();
        if (createDestination) {
            operations.add(PlannedOperation.createFolder(destinationFolder));
        }
        for (FileMatch match : refined.matches()) {
            operations.add(PlannedOperation.transfer(
                    move ? MutationKind.MOVE : MutationKind.COPY,
                    match.path(),
                    destinationFolder.resolve(match.fileName())));
        }
        int files = refined.matches().size();
        String description = (move ? "Move " : "Copy ") + (files == 1 ? "1 file" : files + " files")
                + " into " + destinationFolder
                + (createDestination ? " (folder will be created)" : "");
        PendingConfirmation pending = new PendingConfirmation(
                description,
                move ? MutationKind.MOVE : MutationKind.COPY,
                move ? RiskLevel.HIGH : RiskLevel.MEDIUM,
                destinationFolder.toString(),
                refined.matches().stream()
                        .map(match -> new PendingConfirmation.PlannedFile(
                                match.path(), destinationFolder.resolve(match.fileName())))
                        .toList());
        requireConfirmed(pending);
        return operations;
    }

    // ------------------------------------------------------------------
    // Sprint 4A: conversational context (refinement, selection, pronouns,
    // typed confirmation follow-up). All state transitions are serialized
    // on {@link #stateLock}; execution itself stays outside the lock.
    // ------------------------------------------------------------------

    /**
     * Runs the refined search: constraints not restated by the refinement
     * plan are inherited from the stored previous query (never from text),
     * the replacement search is executed, and the result becomes the new
     * current result set (clearing any explicit selection that dropped out).
     */
    private CommandResult executeRefinement(
            CommandPlan.RefineSearch plan,
            Submission cancellation,
            UUID requestId,
            Consumer<ProgressEvent> onProgress) throws ServiceException {
        FileSearchQuery previous = sessionState.lastSearchQuery()
                .orElseThrow(() -> new ServiceException(error(
                        ErrorCode.INVALID_COMMAND,
                        "No previous search to refine; run a find command first")));
        FileSearchQuery.Refinement refinement = plan.refinement();
        FileSearchQuery combined = previous.refined(refinement);
        emit(onProgress, requestId, ProgressStage.EXECUTING, "Scanning files", 0, OptionalLong.empty());
        FileSearchResult result = fileSearchService.search(combined, cancellation,
                visited -> emit(onProgress, requestId, ProgressStage.EXECUTING,
                        "Scanning files", visited, OptionalLong.of(combined.scanLimit())));
        sessionState.setSearchResult(result, combined);
        return result;
    }

    /**
     * Deterministically selects the newest or oldest file of the current
     * result set, stores it as the explicit selection, and opens it. Ordering
     * is by last-modified time; equal timestamps tie-break by absolute path
     * string so the choice never depends on filesystem iteration order.
     */
    private CommandResult executeSelection(CommandPlan.SelectFile plan, Submission cancellation)
            throws ServiceException {
        FileSearchResult source = requireLastResult();
        if (source.matches().isEmpty()) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND, "The current result set is empty; nothing to select"));
        }
        Comparator<FileMatch> byAge = Comparator.comparing(FileMatch::modifiedAt);
        Comparator<FileMatch> deterministic = plan.target() == CommandPlan.SelectFile.Target.NEWEST
                ? byAge.thenComparing(match -> match.path().toString())
                : byAge.thenComparing(match -> match.path().toString()).reversed();
        FileMatch chosen = source.matches().stream().max(deterministic).orElseThrow();
        SelectedFileResult selected = new SelectedFileResult(
                chosen.path(), chosen.fileName(), chosen.sizeBytes(), chosen.modifiedAt());
        sessionState.setSelection(selected);
        // "open the newest" is an open command: the selection is both stored
        // for pronoun follow-ups and handed to the platform opener seam.
        if (!Files.exists(chosen.path(), LinkOption.NOFOLLOW_LINKS)) {
            sessionState.clearSelection();
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND,
                    "The selected file is no longer present: " + chosen.path()));
        }
        requireInScope(chosen.path().toAbsolutePath().normalize());
        fileOpener.open(chosen.path(), cancellation);
        return selected;
    }

    /**
     * Opens the single contextual file referent: the explicit selection when
     * present, otherwise the sole file of the current result set. Ambiguous
     * or missing referents are honest rejections, never guesses.
     */
    private CommandResult executeOpenSelected(Submission cancellation) throws ServiceException {
        SelectedFileResult target = sessionState.referent();
        if (target == null) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND,
                    "No single file is selected; use 'open the newest' or select one file first"));
        }
        if (!Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            sessionState.clearSelection();
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND,
                    "The selected file is no longer present: " + target.path()));
        }
        requireInScope(target.path().toAbsolutePath().normalize());
        fileOpener.open(target.path(), cancellation);
        return target;
    }

    /**
     * Executes the exact pending confirmed operation: the typed operations
     * resolved and previewed when the mutation command ran. Nothing is
     * re-parsed and no path is re-resolved.
     */
    private CommandResult executePendingMutation(
            UUID requestId,
            Consumer<ProgressEvent> onProgress) throws ServiceException {
        List<PlannedOperation> operations;
        PendingConfirmation preview;
        synchronized (stateLock) {
            operations = pendingOperations;
            preview = pendingConfirmation;
        }
        if (operations == null) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND, "There is no pending operation to confirm"));
        }
        requireMutationSupport();
        emit(onProgress, requestId, ProgressStage.EXECUTING, "Applying file operations",
                0, OptionalLong.empty());
        List<MutationReceipt.Entry> entries = new ArrayList<>();
        for (PlannedOperation operation : operations) {
            entries.add(operation.apply(requestId, mutationService));
        }
        synchronized (stateLock) {
            pendingConfirmation = null;
            pendingOperations = null;
        }
        sessionState.invalidateSearchResult();
        boolean allFailed = entries.stream().allMatch(entry -> entry.status() == OperationStatus.FAILED);
        if (allFailed) {
            StructuredError first = entries.getFirst().error().orElseThrow(
                    () -> new IllegalStateException("failed entry without error"));
            throw new ServiceException(first);
        }
        return new MutationReceipt(preview.kind(), entries);
    }

    /** Clears the pending confirmation without any filesystem effect. */
    private CommandResult cancelPendingMutation() throws ServiceException {
        PendingConfirmation cancelled;
        synchronized (stateLock) {
            if (pendingConfirmation == null) {
                throw new ServiceException(error(
                        ErrorCode.INVALID_COMMAND, "There is no pending operation to cancel"));
            }
            cancelled = pendingConfirmation;
            pendingConfirmation = null;
            pendingOperations = null;
        }
        return new CancellationReceipt(
                "Cancelled: nothing was changed. " + cancelled.originalCommand());
    }

    /**
     * Resolves the mutation selection against the current result set and
     * stores the explicit selection for pronoun follow-ups. NEWEST and
     * OLDEST use the same deterministic ordering as {@link #executeSelection}.
     */
    private FileSearchResult resolveRefinementFiles(
            CommandPlan.FileMutation.Selection selection, FileSearchResult source) throws ServiceException {
        if (selection == CommandPlan.FileMutation.Selection.LAST_RESULT
                || selection == CommandPlan.FileMutation.Selection.ALL_IN_SCOPE) {
            return source;
        }
        List<FileMatch> pool = source.matches();
        FileMatch chosen = switch (selection) {
            case NEWEST -> pool.stream()
                    .max(Comparator.comparing(FileMatch::modifiedAt)
                            .thenComparing(match -> match.path().toString()))
                    .orElseThrow();
            case OLDEST -> pool.stream()
                    .min(Comparator.comparing(FileMatch::modifiedAt)
                            .thenComparing(match -> match.path().toString()))
                    .orElseThrow();
            case SELECTED -> {
                SelectedFileResult selected = sessionState.referent();
                if (selected == null) {
                    throw new ServiceException(error(
                            ErrorCode.INVALID_COMMAND,
                            "No single file is selected; use 'open the newest' or 'move the newest to …' first"));
                }
                yield pool.stream()
                        .filter(match -> match.path().equals(selected.path()))
                        .findFirst()
                        .orElseThrow(() -> new ServiceException(error(
                                ErrorCode.INVALID_COMMAND,
                                "The selected file is not part of the current result set")));
            }
            default -> throw new IllegalStateException("unexpected selection " + selection);
        };
        SelectedFileResult selectedResult = new SelectedFileResult(
                chosen.path(), chosen.fileName(), chosen.sizeBytes(), chosen.modifiedAt());
        sessionState.setSelection(selectedResult);
        return new FileSearchResult(List.of(chosen), source.visitedFiles(),
                source.resultLimitReached(), source.scanLimitReached());
    }

    private List<PlannedOperation> planRename(CommandPlan.FileMutation plan) throws ServiceException {
        FileSearchResult source = requireLastResult();
        if (source.matches().isEmpty()) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND, "The previous search found no files to rename"));
        }
        // NEWEST/OLDEST/SELECTED all resolve to one deterministic file (and
        // record it as the explicit selection for pronoun follow-ups).
        FileMatch chosen = resolveRefinementFiles(plan.selection(), source).matches().getFirst();
        String newName = plan.name();
        String oldName = chosen.fileName();
        if (newName.lastIndexOf('.') < 0 && oldName.lastIndexOf('.') > 0) {
            newName = newName + oldName.substring(oldName.lastIndexOf('.'));
        }
        Path target = chosen.path().resolveSibling(newName);
        PlannedOperation operation = PlannedOperation.rename(chosen.path(), target);
        confirmSingle(operation, MutationKind.RENAME, RiskLevel.HIGH,
                "Rename " + oldName + " to " + newName);
        return List.of(operation);
    }

    private FileSearchResult requireLastResult() throws ServiceException {
        FileSearchResult result = sessionState.lastSearchResult;
        if (result == null) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND,
                    "No previous search result in this session; run a find command first"));
        }
        return result;
    }

    private Path resolveDestinationFolder(String name) {
        for (Path root : scopeRoots) {
            Path candidate = root.resolve(name);
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        return scopeRoots.getFirst().resolve(name);
    }

    private void confirmSingle(
            PlannedOperation operation,
            MutationKind kind,
            RiskLevel risk,
            String description) throws ServiceException {
        PendingConfirmation pending = new PendingConfirmation(
                description,
                kind,
                risk,
                operation.target().toString(),
                List.of(new PendingConfirmation.PlannedFile(operation.source(), operation.target())));
        requireConfirmed(pending);
    }

    /**
     * Records the preview and, when a handler seam is configured, asks it
     * immediately (denial aborts with {@code CONFIRMATION_DENIED}). Without
     * a handler the operation is deferred: the caller stores the pending
     * preview and the typed "confirm"/"cancel" follow-up decides.
     */
    private void requireConfirmed(PendingConfirmation pending) throws ServiceException {
        lastPendingConfirmation = pending;
        if (confirmationHandler == null) {
            return;
        }
        if (confirmationHandler.confirm(pending) != ConfirmationHandler.Decision.CONFIRMED) {
            throw new ServiceException(error(
                    ErrorCode.CONFIRMATION_DENIED, "Operation cancelled by user"));
        }
    }

    private static MutationKind toReceiptKind(CommandPlan.FileMutation.Kind kind) {
        return switch (kind) {
            case MOVE -> MutationKind.MOVE;
            case COPY -> MutationKind.COPY;
            case RENAME -> MutationKind.RENAME;
            case CREATE_FOLDER -> MutationKind.CREATE_FOLDER;
        };
    }

    private static RiskLevel riskFor(MutationKind kind) {
        return switch (kind) {
            case MOVE, RENAME -> RiskLevel.HIGH;
            case COPY, CREATE_FOLDER -> RiskLevel.MEDIUM;
        };
    }

    // ------------------------------------------------------------------
    // Undo: invert the most recent undoable request, row by row.
    // ------------------------------------------------------------------

    private CommandResult executeUndo(
            Submission cancellation,
            UUID requestId,
            Consumer<ProgressEvent> onProgress) throws ServiceException {
        requireMutationSupport();
        emit(onProgress, requestId, ProgressStage.EXECUTING, "Looking up the last undoable operation",
                0, OptionalLong.empty());
        Optional<UUID> latest = undoJournal.latestUndoableRequest(cancellation);
        if (latest.isEmpty()) {
            throw new ServiceException(error(ErrorCode.INVALID_COMMAND, "Nothing left to undo"));
        }
        List<UndoEntry.JournalRow> rows = undoJournal.rowsForRequest(latest.get(), cancellation);
        List<UndoEntry.JournalRow> resultRows = new ArrayList<>(rows);
        boolean anyAttempt = false;
        for (int index = 0; index < rows.size(); index++) {
            UndoEntry.JournalRow row = rows.get(index);
            if (row.status() != UndoEntry.JournalStatus.ACTIVE) {
                continue;
            }
            anyAttempt = true;
            cancelled(cancellation);
            UndoEntry entry = row.entry();
            Optional<StructuredError> failure = reverse(entry);
            undoJournal.markStatus(
                    entry.id(),
                    failure.isEmpty() ? UndoEntry.JournalStatus.UNDONE : UndoEntry.JournalStatus.FAILED,
                    failure);
            resultRows.set(index,
                    new UndoEntry.JournalRow(entry,
                            failure.isEmpty() ? UndoEntry.JournalStatus.UNDONE : UndoEntry.JournalStatus.FAILED,
                            failure));
        }
        if (!anyAttempt) {
            throw new ServiceException(error(ErrorCode.INVALID_COMMAND, "Nothing left to undo"));
        }
        sessionState.invalidateSearchResult();
        return new UndoResult(latest.get(), resultRows);
    }

    /**
     * Inverts one journalled operation. Undo moves are themselves confined to
     * the configured scope and never overwrite anything: if the original
     * location is occupied again, or the moved file is gone, the row fails
     * and is reported instead of being forced.
     */
    private Optional<StructuredError> reverse(UndoEntry entry) {
        try {
            // Undo of "A -> B" moves B back to A.
            Path current = entry.target().toAbsolutePath().normalize();
            Path original = entry.source().toAbsolutePath().normalize();
            requireInScope(current);
            requireInScope(original);
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                throw new ServiceException(error(
                        ErrorCode.TARGET_EXISTS, "Original location is occupied: " + original));
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new ServiceException(error(
                        ErrorCode.IO_FAILURE, "Moved item is no longer present: " + current));
            }
            try {
                Files.move(current, original, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(current, original);
            } catch (FileAlreadyExistsException e) {
                throw new ServiceException(error(
                        ErrorCode.TARGET_EXISTS, "Original location is occupied: " + original));
            }
            return Optional.empty();
        } catch (ServiceException e) {
            return Optional.of(e.error());
        } catch (IOException | SecurityException e) {
            return Optional.of(new StructuredError(
                    ErrorCode.IO_FAILURE, "Could not undo operation",
                    Optional.ofNullable(e.getMessage())));
        }
    }

    private void requireInScope(Path path) throws ServiceException {
        for (Path root : scopeRoots) {
            if (path.equals(root) || path.startsWith(root)) {
                return;
            }
        }
        throw new ServiceException(error(
                ErrorCode.ACCESS_DENIED, "Path is outside the configured scope: " + path));
    }

    // ------------------------------------------------------------------
    // Persistence and plumbing (unchanged sprint 1 behaviour).
    // ------------------------------------------------------------------

    private CommandOutcome persist(CommandRequest request, CommandOutcome outcome) {
        try {
            historyRepository.save(new HistoryEntry(
                    request.id(), request.originalText(), outcome.status(), outcome.summary(), outcome.error(),
                    request.submittedAt(), outcome.startedAt(), outcome.completedAt()));
            return outcome;
        } catch (ServiceException e) {
            return new CommandOutcome(outcome.requestId(), outcome.status(),
                    outcome.summary() + " (history was not saved: " + e.error().message() + ")",
                    outcome.result(), outcome.error(), outcome.startedAt(), outcome.completedAt());
        }
    }

    private static String summary(CommandResult result) {
        if (result instanceof AppLaunchReceipt receipt) {
            return "Launch requested for " + receipt.displayName();
        }
        if (result instanceof FileSearchResult files) {
            return "Found " + files.matches().size() + " file(s) after visiting "
                    + files.visitedFiles() + " file(s)";
        }
        if (result instanceof MutationReceipt receipt) {
            long applied = receipt.entries().stream()
                    .filter(entry -> entry.status() == OperationStatus.APPLIED).count();
            long failed = receipt.entries().size() - applied;
            return capitalize(receipt.kind().name().toLowerCase(Locale.ROOT))
                    + " completed for " + applied + " item(s)"
                    + (failed == 0 ? "" : ", " + failed + " failed");
        }
        if (result instanceof UndoResult undo) {
            return undo.fullyReversed() ? "Undo completed" : "Undo partially completed; some entries failed";
        }
        if (result instanceof ContentSearchResult content) {
            long failed = content.documents().stream().filter(ContentSearchResult.DocumentHit::extractionFailed).count();
            return "Found " + content.totalMatches() + " match(es) across "
                    + (content.documents().size() - failed) + " document(s)"
                    + (failed == 0 ? "" : ", " + failed + " could not be read");
        }
        if (result instanceof SystemSnapshot) {
            return "System status captured";
        }
        return "Command history loaded";
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void cancelled(Submission submission) throws ServiceException {
        if (submission.isCancellationRequested()) {
            throw new ServiceException(error(ErrorCode.CANCELLED, "Command cancelled"));
        }
    }

    private static CommandOutcome terminal(
            UUID requestId,
            CommandStatus status,
            String summary,
            Optional<CommandResult> result,
            Optional<StructuredError> error,
            Instant started) {
        return new CommandOutcome(requestId, status, summary, result, error, started, Instant.now());
    }

    private static StructuredError error(ErrorCode code, String message) {
        return new StructuredError(code, message, Optional.empty());
    }

    private static void emit(
            Consumer<ProgressEvent> listener,
            UUID requestId,
            ProgressStage stage,
            String message,
            long completed,
            OptionalLong total) {
        safeAccept(listener, new ProgressEvent(requestId, stage, message, completed, total, Instant.now()));
    }

    private static <T> void safeAccept(Consumer<T> listener, T value) {
        try {
            listener.accept(value);
        } catch (RuntimeException ignored) {
            // A UI callback must not alter command execution or persistence.
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            active.values().forEach(Submission::cancel);
        }
    }

    /**
     * Mutable per-session conversational context (sprint 4A): the previous
     * structured query, the current result set, the explicit selection and
     * the pending confirmation. Owned by the gateway, guarded by its state
     * lock, and strictly in-memory per running session — history and undo
     * persistence are unchanged. Failed commands never touch this state.
     */
    public static final class SessionState {
        private volatile FileSearchResult lastSearchResult;
        private volatile FileSearchQuery lastSearchQuery;
        private volatile SelectedFileResult selection;

        public Optional<FileSearchResult> lastSearchResult() {
            return Optional.ofNullable(lastSearchResult);
        }

        Optional<FileSearchQuery> lastSearchQuery() {
            return Optional.ofNullable(lastSearchQuery);
        }

        /** The explicit selection, if one is currently valid. */
        public Optional<SelectedFileResult> selection() {
            return Optional.ofNullable(selection);
        }

        void setSearchResult(FileSearchResult result, FileSearchQuery query) {
            this.lastSearchResult = result;
            this.lastSearchQuery = query;
            this.selection = null;
        }

        void setSelection(SelectedFileResult selected) {
            this.selection = selected;
        }

        void clearSelection() {
            this.selection = null;
        }

        /** Cache invalidation after a mutation or undo changed the filesystem. */
        void invalidateSearchResult() {
            this.lastSearchResult = null;
            this.lastSearchQuery = null;
            this.selection = null;
        }

        /**
         * The one valid contextual referent for "it" / "the file": the
         * explicit selection, or the sole file of the current result set.
         * Returns null when there is no unique referent.
         */
        SelectedFileResult referent() {
            if (selection != null) {
                return selection;
            }
            FileSearchResult current = lastSearchResult;
            if (current == null || current.matches().size() != 1) {
                return null;
            }
            FileMatch only = current.matches().getFirst();
            return new SelectedFileResult(only.path(), only.fileName(), only.sizeBytes(), only.modifiedAt());
        }
    }

    /** One concrete, confirmed-before-execution file operation. */
    private static final class PlannedOperation {
        private final MutationKind kind;
        private final Path source;
        private final Path target;

        private PlannedOperation(MutationKind kind, Path source, Path target) {
            this.kind = kind;
            this.source = source;
            this.target = target;
        }

        static PlannedOperation createFolder(Path target) {
            return new PlannedOperation(MutationKind.CREATE_FOLDER, target, target);
        }

        static PlannedOperation transfer(MutationKind kind, Path source, Path target) {
            return new PlannedOperation(kind, source, target);
        }

        static PlannedOperation rename(Path source, Path target) {
            return new PlannedOperation(MutationKind.RENAME, source, target);
        }

        MutationKind kind() {
            return kind;
        }

        Path source() {
            return source;
        }

        Path target() {
            return target;
        }

        MutationReceipt.Entry apply(UUID requestId, FileMutationService service) {
            try {
                return service.apply(kind, source, target, requestId, CancellationToken.NONE);
            } catch (ServiceException e) {
                return new MutationReceipt.Entry(
                        OperationStatus.FAILED, source, target, riskFor(kind), Optional.of(e.error()));
            }
        }
    }

    private static final class Submission implements CommandSubscription, CancellationToken {
        private final UUID requestId;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Submission(UUID requestId) {
            this.requestId = requestId;
        }

        @Override
        public UUID requestId() {
            return requestId;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        @Override
        public void close() {
            cancel();
        }
    }
}
