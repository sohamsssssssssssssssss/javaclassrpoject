package com.jarvis.integration;

import com.jarvis.api.CancellationReceipt;
import com.jarvis.api.CommandOutcome;
import com.jarvis.api.CommandRequest;
import com.jarvis.api.CommandResult;
import com.jarvis.api.CommandStatus;
import com.jarvis.api.ConfiguredApp;
import com.jarvis.api.CancellationToken;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.FileMutationPreview;
import com.jarvis.api.FileSearchResult;
import com.jarvis.api.FileOpener;
import com.jarvis.api.SelectedFileResult;
import com.jarvis.api.ServiceException;
import com.jarvis.api.UndoResult;
import com.jarvis.core.DefaultCommandGateway;
import com.jarvis.services.app.DesktopAppService;
import com.jarvis.services.files.ContentSearchService;
import com.jarvis.services.files.FileSystemFileService;
import com.jarvis.services.files.ScopedFileMutationService;
import com.jarvis.services.history.SqliteHistoryRepository;
import com.jarvis.services.search.FileSystemFileSearchService;
import com.jarvis.services.system.OshiSystemInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 4A pipeline: bounded conversational file context. Proves search
 * refinement from the previous structured query, deterministic newest/oldest
 * selection with opening, honest pronoun resolution, the typed
 * confirm/cancel follow-up, "undo that" through the existing journal, and
 * the full acceptance conversation. Every filesystem effect is confined to
 * {@link TempDir}; the clock is pinned so date refinements are exact.
 */
class SprintFourAContextTest {
    /** Fixed "now": Saturday 2026-09-19 12:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");
    /** Local midnight of the pinned day, i.e. the start of "today". */
    private static final Instant TODAY_START = Instant.parse("2026-09-19T00:00:00Z");
    private static final Instant YESTERDAY_START = TODAY_START.minusSeconds(86_400);

    @TempDir
    Path temporaryDirectory;

    // ------------------------------------------------------------------
    // Core context: refinement semantics
    // ------------------------------------------------------------------

    @Test
    void initialSearchEstablishesContext() throws Exception {
        Path root = scope();
        pdf(root, "invoice.pdf", "invoice total", YESTERDAY_START.plusSeconds(3_600));
        try (Runtime runtime = new Runtime()) {
            FileSearchResult found = result(
                    runtime.submit("find pdf files from yesterday"), FileSearchResult.class);
            assertEquals(List.of("invoice.pdf"), names(found));
            assertTrue(runtime.gateway.sessionState().lastSearchResult().isPresent(),
                    "a successful search must establish the session context");
        }
    }

    @Test
    void sizeRefinementInheritsExtensionAndDate() throws Exception {
        Path root = scope();
        pdf(root, "small.pdf", "small body", YESTERDAY_START.plusSeconds(3_600));
        pdf(root, "big.pdf", "big body", YESTERDAY_START.plusSeconds(7_200), 3_000);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find pdf files from yesterday"), FileSearchResult.class);
            FileSearchResult refined = result(
                    runtime.submit("only larger than 2 KB"), FileSearchResult.class);
            assertEquals(List.of("big.pdf"), names(refined),
                    "the refinement must combine with, not replace, the previous query");
        }
    }

    @Test
    void dateRefinementRetainsSizeAndExtension() throws Exception {
        Path root = scope();
        byte[] big = new byte[3_000];
        Files.write(root.resolve("old.txt"), big);
        Files.setLastModifiedTime(root.resolve("old.txt"), FileTime.from(YESTERDAY_START));
        Files.write(root.resolve("new.txt"), big);
        Files.setLastModifiedTime(root.resolve("new.txt"), FileTime.from(TODAY_START));
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files larger than 1 KB"), FileSearchResult.class);
            FileSearchResult refined = result(
                    runtime.submit("only from today"), FileSearchResult.class);
            assertEquals(List.of("new.txt"), names(refined),
                    "the restated date replaces the window; size and extension are retained");
        }
    }

    @Test
    void extensionRefinementRetainsUnrelatedConstraints() throws Exception {
        Path root = scope();
        byte[] big = new byte[3_000];
        Files.write(root.resolve("shot.png"), big);
        Files.setLastModifiedTime(root.resolve("shot.png"), FileTime.from(YESTERDAY_START));
        Files.write(root.resolve("doc.pdf"), big);
        Files.setLastModifiedTime(root.resolve("doc.pdf"), FileTime.from(YESTERDAY_START));
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files larger than 1 KB from yesterday"),
                    FileSearchResult.class);
            assertEquals(0, result(runtime.submit("only larger than 2 KB"), FileSearchResult.class)
                    .matches().size());
            FileSearchResult refined = result(
                    runtime.submit("only pngs"), FileSearchResult.class);
            assertEquals(List.of("shot.png"), names(refined),
                    "the restated extension replaces; size and date constraints are retained");
        }
    }

    @Test
    void newSearchReplacesPreviousResultSet() throws Exception {
        Path root = scope();
        pdf(root, "invoice.pdf", "invoice", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find pdf files from yesterday"), FileSearchResult.class);
            FileSearchResult replaced = result(runtime.submit("find txt files"), FileSearchResult.class);
            assertEquals(0, replaced.matches().size());
            assertEquals(0, result(runtime.submit("only larger than 1 KB"), FileSearchResult.class)
                    .matches().size(),
                    "the refinement applies to the NEW result set, not the replaced one");
        }
    }

    @Test
    void zeroResultRefinementIsHonest() throws Exception {
        Path root = scope();
        pdf(root, "invoice.pdf", "invoice", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find pdf files from yesterday"), FileSearchResult.class);
            FileSearchResult refined = result(
                    runtime.submit("only larger than 500 MB"), FileSearchResult.class);
            assertEquals(0, refined.matches().size(),
                    "an empty refinement result must be reported honestly, not fabricated");
            CommandOutcome open = runtime.submit("open the newest");
            assertEquals(CommandStatus.REJECTED, open.status(),
                    "an empty result set must not produce a selection");
        }
    }

    @Test
    void refinementWithoutPriorSearchRejects() throws Exception {
        scope();
        try (Runtime runtime = new Runtime()) {
            CommandOutcome outcome = runtime.submit("only larger than 20 MB");
            assertEquals(CommandStatus.REJECTED, outcome.status());
            assertEquals(ErrorCode.INVALID_COMMAND, outcome.error().orElseThrow().code());
            assertTrue(outcome.error().orElseThrow().message().contains("No previous search"));
        }
    }

    @Test
    void failedCommandDoesNotDestroyValidContext() throws Exception {
        Path root = scope();
        pdf(root, "invoice.pdf", "invoice", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find pdf files from yesterday"), FileSearchResult.class);
            assertEquals(CommandStatus.REJECTED, runtime.submit("only bigger nonsense").status());
            assertEquals(CommandStatus.REJECTED, runtime.submit("file info absent.txt").status());
            assertEquals(CommandStatus.REJECTED, runtime.submit("confirm").status(),
                    "a failed unrelated command must not disturb the session state");
            FileSearchResult stillThere = result(
                    runtime.submit("only smaller than 500 MB"), FileSearchResult.class);
            assertEquals(List.of("invoice.pdf"), names(stillThere),
                    "failed commands must not corrupt previously valid context");
        }
    }

    // ------------------------------------------------------------------
    // Selection: newest/oldest, ties, pronouns
    // ------------------------------------------------------------------

    @Test
    void newestSelectsCorrectFileAndOpensIt() throws Exception {
        Path root = scope();
        pdf(root, "old.pdf", "old body", YESTERDAY_START);
        pdf(root, "new.pdf", "new body", YESTERDAY_START.plusSeconds(60));
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find pdf files"), FileSearchResult.class);
            SelectedFileResult opened = result(
                    runtime.submit("open the newest"), SelectedFileResult.class);
            assertEquals("new.pdf", opened.fileName());
            assertEquals(List.of("new.pdf"), runtime.opened);
            assertEquals("new.pdf",
                    runtime.gateway.sessionState().selection().orElseThrow().fileName(),
                    "the opened file becomes the selection for pronoun follow-ups");
        }
    }

    @Test
    void oldestSelectsCorrectFile() throws Exception {
        Path root = scope();
        pdf(root, "old.pdf", "old body", YESTERDAY_START);
        pdf(root, "new.pdf", "new body", YESTERDAY_START.plusSeconds(60));
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find pdf files"), FileSearchResult.class);
            SelectedFileResult opened = result(
                    runtime.submit("open the oldest"), SelectedFileResult.class);
            assertEquals("old.pdf", opened.fileName());
            assertEquals(List.of("old.pdf"), runtime.opened);
        }
    }

    @Test
    void timestampTiesBreakByPathDeterministically() throws Exception {
        Path root = scope();
        pdf(root, "b.txt", "same time", YESTERDAY_START);
        pdf(root, "a.txt", "same time", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            SelectedFileResult opened = result(
                    runtime.submit("open the newest"), SelectedFileResult.class);
            assertEquals("b.txt", opened.fileName(),
                    "equal timestamps tie-break by highest absolute path string");
        }
    }

    @Test
    void selectorWithoutResultsRejects() throws Exception {
        scope();
        try (Runtime runtime = new Runtime()) {
            CommandOutcome noSearch = runtime.submit("open the newest");
            assertEquals(CommandStatus.REJECTED, noSearch.status());
            assertEquals(ErrorCode.INVALID_COMMAND, noSearch.error().orElseThrow().code());

            result(runtime.submit("find txt files"), FileSearchResult.class);
            CommandOutcome empty = runtime.submit("open the oldest");
            assertEquals(CommandStatus.REJECTED, empty.status());
            assertTrue(empty.error().orElseThrow().message().contains("empty"));
        }
    }

    @Test
    void selectedFileCanBeReferencedByIt() throws Exception {
        Path root = scope();
        pdf(root, "first.txt", "first body", YESTERDAY_START);
        pdf(root, "second.txt", "second body", YESTERDAY_START.plusSeconds(60));
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            result(runtime.submit("open the newest"), SelectedFileResult.class);
            // "it" resolves to the single explicit selection: only second.txt
            // is moved, first.txt stays where it is, and Review is created.
            CommandOutcome moved = runtime.submit("move it to Review");
            assertEquals(CommandStatus.SUCCEEDED, moved.status(), moved.summary());
            CommandOutcome confirmed = runtime.submit("confirm");
            assertEquals(CommandStatus.SUCCEEDED, confirmed.status(), confirmed.summary());
            assertTrue(Files.exists(root.resolve("Review").resolve("second.txt")));
            assertFalse(Files.exists(root.resolve("second.txt")));
            assertTrue(Files.exists(root.resolve("first.txt")));
        }
    }

    @Test
    void ambiguousPronounReferenceRejects() throws Exception {
        Path root = scope();
        pdf(root, "first.txt", "first body", YESTERDAY_START);
        pdf(root, "second.txt", "second body", YESTERDAY_START.plusSeconds(60));
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            CommandOutcome move = runtime.submit("move it to Review");
            assertEquals(CommandStatus.REJECTED, move.status(),
                    "two plausible referents without a selection must fail honestly");
            assertEquals(ErrorCode.INVALID_COMMAND, move.error().orElseThrow().code());
            assertFalse(Files.exists(root.resolve("Review")));
        }
    }

    @Test
    void staleSelectedFileRejectsHonestly() throws Exception {
        Path root = scope();
        pdf(root, "single.txt", "single body", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            result(runtime.submit("open the newest"), SelectedFileResult.class);
            Files.delete(root.resolve("single.txt"));

            CommandOutcome open = runtime.submit("open the file");
            assertEquals(CommandStatus.REJECTED, open.status(),
                    "a selected file that disappeared externally must fail honestly");
            assertEquals(1, runtime.opened.size(),
                    "the stale selection must not be handed to the opener again");
        }
    }

    // ------------------------------------------------------------------
    // Typed confirmation follow-up
    // ------------------------------------------------------------------

    @Test
    void moveFollowUpStoresPendingConfirmationWithoutDiskEffect() throws Exception {
        Path root = scope();
        pdf(root, "report.txt", "report body", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            CommandOutcome requested = runtime.submit("move these files to Review");
            assertEquals(CommandStatus.SUCCEEDED, requested.status(), requested.summary());
            FileMutationPreview preview =
                    assertInstanceOf(FileMutationPreview.class, requested.result().orElseThrow());
            assertEquals(1, preview.pending().plannedFiles().size());
            assertTrue(Files.exists(root.resolve("report.txt")),
                    "no filesystem mutation may occur before the typed confirm");
            assertFalse(Files.exists(root.resolve("Review")));
        }
    }

    @Test
    void confirmExecutesExactPendingOperationExactlyOnce() throws Exception {
        Path root = scope();
        pdf(root, "report.txt", "report body", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            runtime.submit("move these files to Review");
            CommandOutcome confirmed = runtime.submit("confirm");
            assertEquals(CommandStatus.SUCCEEDED, confirmed.status(), confirmed.summary());
            assertTrue(Files.exists(root.resolve("Review").resolve("report.txt")));
            assertFalse(Files.exists(root.resolve("report.txt")));

            CommandOutcome repeat = runtime.submit("confirm");
            assertEquals(CommandStatus.REJECTED, repeat.status(),
                    "a second confirm must not repeat the executed mutation");
            assertEquals(ErrorCode.INVALID_COMMAND, repeat.error().orElseThrow().code());
        }
    }

    @Test
    void cancelClearsPendingOperationWithoutSideEffects() throws Exception {
        Path root = scope();
        pdf(root, "report.txt", "report body", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            runtime.submit("move these files to Review");
            CommandOutcome cancelled = runtime.submit("cancel");
            assertEquals(CommandStatus.SUCCEEDED, cancelled.status(), cancelled.summary());
            assertInstanceOf(CancellationReceipt.class, cancelled.result().orElseThrow());
            assertTrue(Files.exists(root.resolve("report.txt")), "cancelling changes nothing");

            assertEquals(CommandStatus.REJECTED, runtime.submit("confirm").status(),
                    "the cleared pending confirmation cannot be confirmed afterwards");
        }
    }

    @Test
    void confirmWithoutPendingOperationRejects() throws Exception {
        scope();
        try (Runtime runtime = new Runtime()) {
            CommandOutcome outcome = runtime.submit("confirm");
            assertEquals(CommandStatus.REJECTED, outcome.status());
            assertEquals(ErrorCode.INVALID_COMMAND, outcome.error().orElseThrow().code());
            assertEquals(CommandStatus.REJECTED, runtime.submit("cancel").status());
        }
    }

    // ------------------------------------------------------------------
    // Undo follow-up ("undo that")
    // ------------------------------------------------------------------

    @Test
    void undoThatRestoresTheConfirmedMove() throws Exception {
        Path root = scope();
        pdf(root, "movable.txt", "move me", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            runtime.submit("move these files to Archive");
            runtime.submit("confirm");
            assertTrue(Files.exists(root.resolve("Archive").resolve("movable.txt")));

            CommandOutcome undo = runtime.submit("undo that");
            assertEquals(CommandStatus.SUCCEEDED, undo.status(), undo.summary());
            UndoResult undoResult = assertInstanceOf(UndoResult.class, undo.result().orElseThrow());
            assertTrue(undoResult.fullyReversed());
            assertTrue(Files.exists(root.resolve("movable.txt")));
            assertFalse(Files.exists(root.resolve("Archive").resolve("movable.txt")));

            CommandOutcome again = runtime.submit("undo that");
            assertEquals(CommandStatus.REJECTED, again.status(),
                    "a second undo must not fabricate success");
            assertEquals(ErrorCode.INVALID_COMMAND, again.error().orElseThrow().code());
        }
    }

    @Test
    void contextAfterUndoRefersToValidRestoredState() throws Exception {
        Path root = scope();
        pdf(root, "movable.txt", "move me", YESTERDAY_START);
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);
            runtime.submit("move these files to Archive");
            runtime.submit("confirm");
            runtime.submit("undo that");

            assertEquals(CommandStatus.REJECTED, runtime.submit("move it to Elsewhere").status(),
                    "after a mutation/undo the cached set is invalidated, not replayed");
            assertTrue(runtime.gateway.sessionState().selection().isEmpty());

            FileSearchResult refreshed = result(runtime.submit("find txt files"), FileSearchResult.class);
            assertEquals(List.of("movable.txt"), names(refreshed),
                    "a fresh search over the restored filesystem works");
        }
    }

    // ------------------------------------------------------------------
    // Acceptance conversation (sprint goal §1)
    // ------------------------------------------------------------------

    @Test
    void acceptanceConversationFromSearchToUndo() throws Exception {
        Path root = scope();
        pdf(root, "portrait.pdf", "portrait deck", YESTERDAY_START.plusSeconds(36_000), 21_000_000);
        pdf(root, "landscape.pdf", "landscape deck", YESTERDAY_START.plusSeconds(32_400), 21_000_000);
        try (Runtime runtime = new Runtime()) {
            // 1. find PDFs from yesterday -> real result set.
            FileSearchResult found = result(
                    runtime.submit("find PDFs from yesterday"), FileSearchResult.class);
            assertEquals(2, found.matches().size());

            // 2. only larger than 20 MB -> refines the PREVIOUS query.
            FileSearchResult refined = result(
                    runtime.submit("only larger than 20 MB"), FileSearchResult.class);
            assertEquals(2, refined.matches().size(),
                    "both fixture PDFs are over 20 MB and from yesterday");

            // 3. open the newest -> selects and opens the newest of the
            // CURRENT refined set (portrait: 10:00 > landscape: 09:00).
            SelectedFileResult opened = result(
                    runtime.submit("open the newest"), SelectedFileResult.class);
            assertEquals("portrait.pdf", opened.fileName());
            assertEquals(List.of("portrait.pdf"), runtime.opened);

            // 4. move it to Review -> resolves "it" to portrait.pdf and
            //    stores the pending confirmation. NO MOVE YET.
            CommandOutcome requested = runtime.submit("move it to Review");
            assertEquals(CommandStatus.SUCCEEDED, requested.status(), requested.summary());
            assertFalse(Files.exists(root.resolve("Review")),
                    "no filesystem effect before confirmation");
            assertTrue(Files.exists(root.resolve("portrait.pdf")));

            // 5. confirm -> executes the EXACT pending action. FILE MOVED.
            CommandOutcome confirmed = runtime.submit("confirm");
            assertEquals(CommandStatus.SUCCEEDED, confirmed.status(), confirmed.summary());
            assertTrue(Files.exists(root.resolve("Review").resolve("portrait.pdf")));
            assertFalse(Files.exists(root.resolve("portrait.pdf")));
            assertTrue(Files.exists(root.resolve("landscape.pdf")));

            // 6. undo that -> existing undo architecture. FILE RESTORED.
            CommandOutcome undone = runtime.submit("undo that");
            assertEquals(CommandStatus.SUCCEEDED, undone.status(), undone.summary());
            assertTrue(Files.exists(root.resolve("portrait.pdf")));
            assertFalse(Files.exists(root.resolve("Review").resolve("portrait.pdf")));
        }
    }

    // ------------------------------------------------------------------

    /** Creates the scope folder and populates two text files. */
    private Path scope() throws Exception {
        return Files.createDirectory(temporaryDirectory.resolve("demo"));
    }

    /** Small parseable PDF with a pinned modification time. */
    private static void pdf(Path root, String name, String sentence, Instant modified) throws Exception {
        pdf(root, name, sentence, modified, 0);
    }

    /** Parseable PDF padded to {@code minimumSize} bytes (size-filter fixtures). */
    private static void pdf(Path root, String name, String sentence, Instant modified, long minimumSize)
            throws Exception {
        byte[] document = minimalPdf(sentence);
        if (document.length < minimumSize) {
            byte[] padded = new byte[(int) minimumSize];
            System.arraycopy(document, 0, padded, 0, document.length);
            document = padded;
        }
        Path file = root.resolve(name);
        Files.write(file, document);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    /** Byte-accurate minimal PDF with a correct xref table (see DocumentExtractionFormatsTest). */
    private static byte[] minimalPdf(String sentence) {
        String content = "BT /F1 12 Tf 72 720 Td (" + sentence + ") Tj ET";
        String[] objects = {
                "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n",
                "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n",
                "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]"
                        + "/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n",
                "4 0 obj<</Length " + content.length() + ">>stream\n" + content + "\nendstream\nendobj\n",
                "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n"
        };
        StringBuilder body = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (String object : objects) {
            offsets.add(body.length());
            body.append(object);
        }
        int xrefOffset = body.length();
        body.append("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) {
            body.append(String.format("%010d 00000 n \n", offset));
        }
        body.append("trailer<</Size 6/Root 1 0 R>>\nstartxref\n")
                .append(xrefOffset)
                .append("\n%%EOF\n");
        return body.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static List<String> names(FileSearchResult result) {
        return result.matches().stream().map(match -> match.fileName()).toList();
    }

    private static <T extends CommandResult> T result(CommandOutcome outcome, Class<T> type) {
        assertEquals(CommandStatus.SUCCEEDED, outcome.status(), outcome.summary());
        return type.cast(outcome.result().orElseThrow());
    }

    private final class Runtime implements AutoCloseable {
        final ExecutorService executor;
        final SqliteHistoryRepository history;
        final DefaultCommandGateway gateway;
        final List<String> opened = new ArrayList<>();

        Runtime() {
            executor = new ThreadPoolExecutor(
                    2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
            history = new SqliteHistoryRepository(temporaryDirectory.resolve("history.db"));
            Path scope = temporaryDirectory.resolve("demo");
            FileOpener recorder = (path, cancellation) -> {
                opened.add(path.getFileName().toString());
            };
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
                    null,
                    Clock.fixed(NOW, ZONE),
                    new FileSystemFileService(),
                    new ContentSearchService(),
                    recorder);
        }

        CommandOutcome submit(String text) throws Exception {
            CompletableFuture<CommandOutcome> completion = new CompletableFuture<>();
            gateway.submit(CommandRequest.create(text), ignored -> { }, completion::complete);
            return completion.get(15, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            gateway.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            history.close();
        }
    }
}
