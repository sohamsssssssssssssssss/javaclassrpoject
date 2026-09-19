package com.jarvis.core;

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
import com.jarvis.api.FileMutationService;
import com.jarvis.api.FileSearchResult;
import com.jarvis.api.FileSearchService;
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
    private final SessionState sessionState = new SessionState();
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

    /** Sprint 4 composition: adds file-intelligence and document content search. */
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
            sessionState.lastSearchResult = result;
            return result;
        }
        if (plan instanceof CommandPlan.FileMutation mutation) {
            return executeMutation(mutation, cancellation, requestId, onProgress);
        }
        if (plan instanceof CommandPlan.Undo) {
            return executeUndo(cancellation, requestId, onProgress);
        }
        if (plan instanceof CommandPlan.ListFiles) {
            FileSearchResult listed = listScopeFiles();
            // A listing acts like any other search: it seeds the session
            // context so "list files" + "move these files to X" composes.
            sessionState.lastSearchResult = listed;
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
        List<PlannedOperation> operations = switch (plan.selection()) {
            case ALL_IN_SCOPE -> planCreateFolder(plan);
            case LAST_RESULT -> planTransfer(plan);
            case NEWEST -> planRename(plan);
        };
        emit(onProgress, requestId, ProgressStage.EXECUTING, "Applying file operations",
                0, OptionalLong.empty());
        List<MutationReceipt.Entry> entries = new ArrayList<>();
        for (PlannedOperation operation : operations) {
            cancelled(cancellation);
            entries.add(operation.apply(requestId, mutationService));
        }
        // Cached search results no longer reflect the filesystem.
        sessionState.lastSearchResult = null;
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
        Path destinationFolder = resolveDestinationFolder(plan.name());
        boolean createDestination = !Files.isDirectory(destinationFolder, LinkOption.NOFOLLOW_LINKS);
        List<PlannedOperation> operations = new ArrayList<>();
        if (createDestination) {
            operations.add(PlannedOperation.createFolder(destinationFolder));
        }
        for (FileMatch match : source.matches()) {
            operations.add(PlannedOperation.transfer(
                    move ? MutationKind.MOVE : MutationKind.COPY,
                    match.path(),
                    destinationFolder.resolve(match.fileName())));
        }
        int files = source.matches().size();
        String description = (move ? "Move " : "Copy ") + (files == 1 ? "1 file" : files + " files")
                + " into " + destinationFolder
                + (createDestination ? " (folder will be created)" : "");
        PendingConfirmation pending = new PendingConfirmation(
                description,
                move ? MutationKind.MOVE : MutationKind.COPY,
                move ? RiskLevel.HIGH : RiskLevel.MEDIUM,
                destinationFolder.toString(),
                source.matches().stream()
                        .map(match -> new PendingConfirmation.PlannedFile(
                                match.path(), destinationFolder.resolve(match.fileName())))
                        .toList());
        requireConfirmed(pending);
        return operations;
    }

    private List<PlannedOperation> planRename(CommandPlan.FileMutation plan) throws ServiceException {
        FileSearchResult source = requireLastResult();
        if (source.matches().isEmpty()) {
            throw new ServiceException(error(
                    ErrorCode.INVALID_COMMAND, "The previous search found no files to rename"));
        }
        FileMatch newest = source.matches().stream()
                .max(Comparator.comparing(FileMatch::modifiedAt)
                        .thenComparing(match -> match.path().toString()))
                .orElseThrow();
        String newName = plan.name();
        String oldName = newest.fileName();
        if (newName.lastIndexOf('.') < 0 && oldName.lastIndexOf('.') > 0) {
            newName = newName + oldName.substring(oldName.lastIndexOf('.'));
        }
        Path target = newest.path().resolveSibling(newName);
        PlannedOperation operation = PlannedOperation.rename(newest.path(), target);
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

    private void requireConfirmed(PendingConfirmation pending) throws ServiceException {
        if (confirmationHandler == null) {
            throw new ServiceException(error(
                    ErrorCode.CONFIRMATION_REQUIRED,
                    "This operation needs confirmation, but no confirmation handler is configured"));
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
        sessionState.lastSearchResult = null;
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
     * Mutable per-session context. Sprint 2 keeps the minimal state the
     * plan's context phase needs: the most recent successful search result,
     * cleared after every mutation or undo so stale selections are never
     * replayed against a changed filesystem.
     */
    public static final class SessionState {
        private volatile FileSearchResult lastSearchResult;

        public Optional<FileSearchResult> lastSearchResult() {
            return Optional.ofNullable(lastSearchResult);
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
