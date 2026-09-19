package com.jarvis.integration;

import com.jarvis.api.CommandGateway;
import com.jarvis.api.CommandOutcome;
import com.jarvis.api.CommandRequest;
import com.jarvis.api.CommandResult;
import com.jarvis.api.CommandStatus;
import com.jarvis.api.ConfiguredApp;
import com.jarvis.api.ConfirmationHandler;
import com.jarvis.api.CancellationToken;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.FileMatch;
import com.jarvis.api.FileSearchResult;
import com.jarvis.api.MutationReceipt;
import com.jarvis.api.PendingConfirmation;
import com.jarvis.api.ServiceException;
import com.jarvis.api.UndoResult;
import com.jarvis.core.DefaultCommandGateway;
import com.jarvis.services.app.DesktopAppService;
import com.jarvis.services.files.ScopedFileMutationService;
import com.jarvis.services.history.SqliteHistoryRepository;
import com.jarvis.services.search.FileSystemFileSearchService;
import com.jarvis.services.system.OshiSystemInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 2 pipeline: the typed plan, confirmation preview, real file
 * mutations inside the configured scope, SQLite-backed undo and session
 * invalidation. No test launches applications; every filesystem effect is
 * confined to {@link TempDir} folders (docs rule 25/26).
 */
class SprintTwoPipelineTest {
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @TempDir
    Path temporaryDirectory;

    @Test
    void moveFlowConfirmsAppliesAndUndoRestores() throws Exception {
        Path root = prepareDemoFiles();
        try (Runtime runtime = runtime(ConfirmationHandler.Decision.CONFIRMED)) {
            FileSearchResult found = result(runtime.submit("find txt files"), FileSearchResult.class);
            assertEquals(2, found.matches().size());

            CommandOutcome moved = runtime.submit("move these files to Review");
            assertEquals(CommandStatus.SUCCEEDED, moved.status(), moved.summary());
            assertEquals(1, runtime.confirmations.handled.size());
            PendingConfirmation preview = runtime.confirmations.handled.getFirst();
            assertEquals(2, preview.plannedFiles().size());
            assertTrue(Files.isDirectory(root.resolve("Review")));
            assertTrue(Files.exists(root.resolve("Review").resolve("notes.txt")));
            assertTrue(Files.exists(root.resolve("Review").resolve("report.txt")));
            assertFalse(Files.exists(root.resolve("notes.txt")));

            MutationReceipt receipt = assertInstanceOf(MutationReceipt.class, moved.result().orElseThrow());
            assertEquals(3, receipt.entries().size()); // folder creation + two moves

            CommandOutcome undo = runtime.submit("undo");
            assertEquals(CommandStatus.SUCCEEDED, undo.status(), undo.summary());
            UndoResult undoResult = assertInstanceOf(UndoResult.class, undo.result().orElseThrow());
            assertTrue(undoResult.fullyReversed());
            assertTrue(Files.exists(root.resolve("notes.txt")));
            assertTrue(Files.exists(root.resolve("report.txt")));
            assertTrue(Files.isDirectory(root.resolve("Review")),
                    "folder creation is deliberately not undone");
        }
    }

    @Test
    void denialLeavesTheFilesystemUntouched() throws Exception {
        Path root = prepareDemoFiles();
        try (Runtime runtime = runtime(ConfirmationHandler.Decision.DENIED)) {
            runtime.submit("find txt files");
            CommandOutcome outcome = runtime.submit("move these files to Archive");
            assertEquals(CommandStatus.REJECTED, outcome.status());
            assertEquals(ErrorCode.CONFIRMATION_DENIED, outcome.error().orElseThrow().code());
            assertTrue(Files.exists(root.resolve("notes.txt")));
            assertFalse(Files.exists(root.resolve("Archive")));

            CommandOutcome undo = runtime.submit("undo");
            assertEquals(CommandStatus.REJECTED, undo.status());
            assertEquals(ErrorCode.INVALID_COMMAND, undo.error().orElseThrow().code());
        }
    }

    @Test
    void copyIsNotUndoable() throws Exception {
        Path root = prepareDemoFiles();
        try (Runtime runtime = runtime(ConfirmationHandler.Decision.CONFIRMED)) {
            runtime.submit("find txt files");
            CommandOutcome copied = runtime.submit("copy these files to Backup");
            assertEquals(CommandStatus.SUCCEEDED, copied.status(), copied.summary());
            assertTrue(Files.exists(root.resolve("Backup").resolve("report.txt")));
            assertTrue(Files.exists(root.resolve("report.txt")), "copy keeps the original");

            CommandOutcome undo = runtime.submit("undo");
            assertEquals(CommandStatus.REJECTED, undo.status(),
                    "copies are deliberately not journalled as undoable");
        }
    }

    @Test
    void renameTargetsTheNewestOfTheLastResult() throws Exception {
        Path root = prepareDemoFiles();
        try (Runtime runtime = runtime(ConfirmationHandler.Decision.CONFIRMED)) {
            FileSearchResult found = result(
                    runtime.submit("find txt files from yesterday"), FileSearchResult.class);
            assertEquals(List.of("notes.txt"), found.matches().stream()
                    .map(FileMatch::fileName).toList());

            CommandOutcome renamed = runtime.submit("rename the newest to summary");
            assertEquals(CommandStatus.SUCCEEDED, renamed.status(), renamed.summary());
            assertTrue(Files.exists(root.resolve("summary.txt")),
                    "the extension is preserved when the new name has none");
            assertFalse(Files.exists(root.resolve("notes.txt")));

            CommandOutcome undo = runtime.submit("undo");
            assertEquals(CommandStatus.SUCCEEDED, undo.status(), undo.summary());
            assertTrue(Files.exists(root.resolve("notes.txt")));
        }
    }

    @Test
    void createFolderRequiresConfirmationAndRejectsDuplicates() throws Exception {
        Path root = prepareDemoFiles();
        try (Runtime runtime = runtime(ConfirmationHandler.Decision.CONFIRMED)) {
            CommandOutcome created = runtime.submit("create folder called College");
            assertEquals(CommandStatus.SUCCEEDED, created.status(), created.summary());
            assertTrue(Files.isDirectory(root.resolve("College")));

            CommandOutcome duplicate = runtime.submit("create folder called College");
            assertEquals(CommandStatus.REJECTED, duplicate.status());
            assertEquals(ErrorCode.TARGET_EXISTS, duplicate.error().orElseThrow().code());
        }
    }

    @Test
    void selectionCommandsRequireAPreviousSearch() throws Exception {
        prepareDemoFiles();
        try (Runtime runtime = runtime(ConfirmationHandler.Decision.CONFIRMED)) {
            CommandOutcome move = runtime.submit("move these files to Review");
            assertEquals(CommandStatus.REJECTED, move.status());
            assertEquals(ErrorCode.INVALID_COMMAND, move.error().orElseThrow().code());

            CommandOutcome rename = runtime.submit("rename the newest to summary");
            assertEquals(CommandStatus.REJECTED, rename.status());
        }
    }

    @Test
    void cachedResultsAreInvalidatedAfterAMutation() throws Exception {
        Path root = prepareDemoFiles();
        try (Runtime runtime = runtime(ConfirmationHandler.Decision.CONFIRMED)) {
            runtime.submit("find txt files");
            CommandOutcome moved = runtime.submit("move these files to Review");
            assertEquals(CommandStatus.SUCCEEDED, moved.status(), moved.summary());

            CommandOutcome stale = runtime.submit("rename the newest to summary");
            assertEquals(CommandStatus.REJECTED, stale.status(),
                    "stale selections must never be replayed against a changed filesystem");
        }
    }

    @Test
    void withoutAHandlerTheTypedConfirmFlowDefersAndExecutes() throws Exception {
        Path root = prepareDemoFiles();
        try (Runtime runtime = runtime(null)) {
            CommandOutcome created = runtime.submit("create folder called College");
            assertEquals(CommandStatus.SUCCEEDED, created.status(), created.summary());
            assertFalse(Files.exists(root.resolve("College")),
                    "nothing may happen before the typed confirmation");

            CommandOutcome confirmed = runtime.submit("confirm");
            assertEquals(CommandStatus.SUCCEEDED, confirmed.status(), confirmed.summary());
            assertTrue(Files.isDirectory(root.resolve("College")));

            CommandOutcome repeat = runtime.submit("confirm");
            assertEquals(CommandStatus.REJECTED, repeat.status(),
                    "a second confirm must not repeat the executed operation");
            assertEquals(ErrorCode.INVALID_COMMAND, repeat.error().orElseThrow().code());
        }
    }

    // ------------------------------------------------------------------

    /** Two text files: notes.txt modified yesterday, report.txt now (newest). */
    private Path prepareDemoFiles() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("demo"));
        Path notes = Files.writeString(root.resolve("notes.txt"), "yesterday notes");
        Files.setLastModifiedTime(notes, FileTime.from(NOW.minusSeconds(86_400)));
        Files.writeString(root.resolve("report.txt"), "today report");
        return root;
    }

    private Runtime runtime(ConfirmationHandler.Decision scriptedDecision) {
        return new Runtime(scriptedDecision);
    }

    private final class Runtime implements AutoCloseable {
        final ExecutorService executor;
        final SqliteHistoryRepository history;
        final DefaultCommandGateway gateway;
        final RecordingConfirmation confirmations = new RecordingConfirmation();

        Runtime(ConfirmationHandler.Decision scriptedDecision) {
            executor = new ThreadPoolExecutor(
                    2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
            history = new SqliteHistoryRepository(temporaryDirectory.resolve("history.db"));
            ConfirmationHandler handler = scriptedDecision == null
                    ? null
                    : pending -> {
                        confirmations.add(pending);
                        return scriptedDecision;
                    };
            Path scope = temporaryDirectory.resolve("demo");
            gateway = new DefaultCommandGateway(
                    new DesktopAppService(List.of(
                            new ConfiguredApp("calculator", "Calculator", Set.of("calculator")))),
                    new FileSystemFileSearchService(List.of(scope)),
                    new OshiSystemInfoService(),
                    history,
                    executor,
                    new ScopedFileMutationService(List.of(scope), history),
                    history,
                    List.of(scope),
                    handler,
                    Clock.fixed(NOW, ZONE));
        }

        CommandOutcome submit(String text) throws Exception {
            CompletableFuture<CommandOutcome> completion = new CompletableFuture<>();
            gateway.submit(CommandRequest.create(text), ignored -> { }, completion::complete);
            return completion.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            gateway.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            history.close();
        }
    }

    private static final class RecordingConfirmation implements ConfirmationHandler {
        final List<PendingConfirmation> handled = new ArrayList<>();

        void add(PendingConfirmation pending) {
            handled.add(pending);
        }

        @Override
        public Decision confirm(PendingConfirmation pending) {
            throw new AssertionError("scripted confirmation must be used");
        }
    }

    private static <T extends CommandResult> T result(CommandOutcome outcome, Class<T> type) {
        assertEquals(CommandStatus.SUCCEEDED, outcome.status(), outcome.summary());
        return type.cast(outcome.result().orElseThrow());
    }
}
