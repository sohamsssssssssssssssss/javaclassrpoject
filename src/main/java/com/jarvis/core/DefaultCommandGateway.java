package com.jarvis.core;

import com.jarvis.api.*;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DefaultCommandGateway implements CommandGateway {
    private static final int HISTORY_LIMIT = 10;

    private final AppService appService;
    private final FileSearchService fileSearchService;
    private final SystemInfoService systemInfoService;
    private final HistoryRepository historyRepository;
    private final ExecutorService executor;
    private final CommandParser parser = new CommandParser();
    private final ConcurrentHashMap<UUID, Submission> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultCommandGateway(
            AppService appService,
            FileSearchService fileSearchService,
            SystemInfoService systemInfoService,
            HistoryRepository historyRepository,
            ExecutorService executor) {
        this.appService = java.util.Objects.requireNonNull(appService, "appService");
        this.fileSearchService = java.util.Objects.requireNonNull(fileSearchService, "fileSearchService");
        this.systemInfoService = java.util.Objects.requireNonNull(systemInfoService, "systemInfoService");
        this.historyRepository = java.util.Objects.requireNonNull(historyRepository, "historyRepository");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
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
            emit(onProgress, request.id(), ProgressStage.EXECUTING, "Executing command", 0, OptionalLong.empty());
            CommandResult result = executePlan(plan, submission, request.id(), onProgress);
            cancelled(submission);
            outcome = terminal(request.id(), CommandStatus.SUCCEEDED, summary(result),
                    Optional.of(result), Optional.empty(), started);
        } catch (CommandParseException e) {
            outcome = terminal(request.id(), CommandStatus.REJECTED, e.error().message(),
                    Optional.empty(), Optional.of(e.error()), started);
        } catch (ServiceException e) {
            CommandStatus status = switch (e.error().code()) {
                case INVALID_COMMAND, UNKNOWN_APP -> CommandStatus.REJECTED;
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
            return fileSearchService.search(find.query(), cancellation,
                    visited -> emit(onProgress, requestId, ProgressStage.EXECUTING,
                            "Scanning files", visited, OptionalLong.of(find.query().scanLimit())));
        }
        if (plan instanceof CommandPlan.SystemStatus) {
            return systemInfoService.snapshot(cancellation);
        }
        return new HistoryResult(historyRepository.recent(HISTORY_LIMIT, cancellation));
    }

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
            return "Found " + files.matches().size() + " PDF file(s) after visiting "
                    + files.visitedFiles() + " file(s)";
        }
        if (result instanceof SystemSnapshot) {
            return "System status captured";
        }
        return "Command history loaded";
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
