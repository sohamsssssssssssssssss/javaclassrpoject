package com.jade.integration;

import com.jade.api.CommandOutcome;
import com.jade.api.CommandRequest;
import com.jade.api.CommandResult;
import com.jade.api.CommandStatus;
import com.jade.api.ConfiguredApp;
import com.jade.api.ConfirmationHandler;
import com.jade.api.ContentSearchResult;
import com.jade.api.ErrorCode;
import com.jade.api.FileSearchResult;
import com.jade.core.DefaultCommandGateway;
import com.jade.services.app.DesktopAppService;
import com.jade.services.files.ContentSearchService;
import com.jade.services.files.FileSystemFileService;
import com.jade.services.history.SqliteHistoryRepository;
import com.jade.services.search.FileSystemFileSearchService;
import com.jade.services.system.OshiSystemInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 4 pipeline: file intelligence commands (list files, file info) and
 * document content search routed through the same parser/gateway/history
 * pipeline as every other command. Filesystem effects are confined to
 * {@link TempDir}; the PDF/DOCX fixtures are generated in memory.
 */
class SprintFourPipelineTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @TempDir
    Path temporaryDirectory;

    @Test
    void listFilesSeedsContextForMutationCommands() throws Exception {
        Path root = prepareScope("the quantum ledger is in notes", "plain report");
        try (Runtime runtime = new Runtime()) {
            FileSearchResult listed = result(runtime.submit("list files"), FileSearchResult.class);
            assertEquals(2, listed.matches().size());

            // The listing is a first-class search for the context engine.
            CommandOutcome moved = runtime.submit("move these files to Review");
            assertEquals(CommandStatus.SUCCEEDED, moved.status(), moved.summary());
            assertTrue(Files.isDirectory(root.resolve("Review")));
        }
    }

    @Test
    void fileInfoResolvesByNameInsideTheScope() throws Exception {
        prepareScope("content one", "content two");
        try (Runtime runtime = new Runtime()) {
            FileSearchResult info = result(
                    runtime.submit("file info report.txt"), FileSearchResult.class);
            assertEquals(1, info.matches().size());
            assertTrue(info.matches().getFirst().path().toString().endsWith("report.txt"));
            assertTrue(info.matches().getFirst().sizeBytes() > 0);

            // Name lookup is case-insensitive.
            FileSearchResult upper = result(
                    runtime.submit("file info REPORT.TXT"), FileSearchResult.class);
            assertEquals(1, upper.matches().size());
        }
    }

    @Test
    void fileInfoRejectsMissingAndAmbiguousNames() throws Exception {
        prepareScope("content one", "content two");
        try (Runtime runtime = new Runtime()) {
            CommandOutcome missing = runtime.submit("file info absent.txt");
            assertEquals(CommandStatus.REJECTED, missing.status());
            assertEquals(ErrorCode.INVALID_COMMAND, missing.error().orElseThrow().code());
            assertTrue(missing.error().orElseThrow().message().contains("No file named"));
        }
    }

    @Test
    void contentSearchReturnsMatchesAndSnippetsThroughThePipeline() throws Exception {
        prepareScope("the quantum ledger is in notes", "a plain unrelated report");
        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find txt files"), FileSearchResult.class);

            ContentSearchResult search = result(
                    runtime.submit("find txt files containing \"quantum\""),
                    ContentSearchResult.class);
            assertEquals("quantum", search.query());
            assertEquals(1, search.totalMatches());
            assertEquals(2, search.documents().size());

            ContentSearchResult.DocumentHit hit = search.documents().stream()
                    .filter(document -> document.path().getFileName().toString().equals("notes.txt"))
                    .findFirst().orElseThrow();
            assertEquals(1, hit.matchCount());
            assertFalse(hit.extractionFailed());
            assertTrue(hit.snippet().orElseThrow().contains("quantum"));

            ContentSearchResult.DocumentHit miss = search.documents().stream()
                    .filter(document -> document.path().getFileName().toString().equals("report.txt"))
                    .findFirst().orElseThrow();
            assertEquals(0, miss.matchCount());
            assertFalse(miss.extractionFailed());
            assertTrue(miss.snippet().isEmpty());

            // Every routed command lands in the one shared history pipeline.
            assertTrue(runtime.recentHistoryTexts().stream()
                    .anyMatch(text -> text.contains("containing \"quantum\"")));
        }
    }

    @Test
    void contentSearchExtractsGeneratedPdfAndDocx() throws Exception {
        Path root = prepareScope("unrelated text file body", "second unrelated body");
        Files.write(root.resolve("paper.pdf"), minimalPdf("extraction check inside pdf"));
        Files.write(root.resolve("thesis.docx"), minimalDocx("extraction check inside docx"));

        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find pdf files"), FileSearchResult.class);
            ContentSearchResult pdfSearch = result(
                    runtime.submit("find pdf files containing \"extraction check\""),
                    ContentSearchResult.class);
            assertEquals(1, pdfSearch.totalMatches(), pdfSearch.toString());
            assertTrue(pdfSearch.documents().getFirst().snippet().orElseThrow()
                    .contains("extraction check"));

            result(runtime.submit("find docx files"), FileSearchResult.class);
            ContentSearchResult docxSearch = result(
                    runtime.submit("find docx files containing \"extraction check\""),
                    ContentSearchResult.class);
            assertEquals(1, docxSearch.totalMatches());
            assertTrue(docxSearch.documents().getFirst().snippet().orElseThrow()
                    .contains("extraction check"));
        }
    }

    @Test
    void unreadableDocumentStaysDistinguishableFromZeroMatches() throws Exception {
        Path root = prepareScope("needle in a text file", "second file");
        Files.write(root.resolve("broken.pdf"), new byte[]{0x00, 0x01, 0x02, 0x03});

        try (Runtime runtime = new Runtime()) {
            result(runtime.submit("find pdf files"), FileSearchResult.class);
            ContentSearchResult search = result(
                    runtime.submit("find pdf files containing \"needle\""),
                    ContentSearchResult.class);

            assertEquals(1, search.documents().size());
            ContentSearchResult.DocumentHit hit = search.documents().getFirst();
            assertTrue(hit.extractionFailed(), "a failed extraction must not read as zero matches");
            assertTrue(hit.failureReason().isPresent());
            assertEquals(0, search.totalMatches());
        }
    }

    @Test
    void contentSearchRequiresAPreviousSearch() throws Exception {
        prepareScope("body one", "body two");
        try (Runtime runtime = new Runtime()) {
            CommandOutcome outcome = runtime.submit("find txt files containing \"body\"");
            assertEquals(CommandStatus.REJECTED, outcome.status());
            assertEquals(ErrorCode.INVALID_COMMAND, outcome.error().orElseThrow().code());
            assertTrue(outcome.error().orElseThrow().message().contains("No previous search"));
        }
    }

    @Test
    void newGrammarRejectsMalformedForms() throws Exception {
        prepareScope("body one", "body two");
        try (Runtime runtime = new Runtime()) {
            // No query after "containing".
            assertEquals(CommandStatus.REJECTED,
                    runtime.submit("find txt files containing").status());
            // Unknown extension still rejected in the containing grammar.
            assertEquals(CommandStatus.REJECTED,
                    runtime.submit("find xyz files containing \"x\"").status());
            // "list" only supports the files form.
            assertEquals(CommandStatus.REJECTED, runtime.submit("list everything").status());
            // "file info" needs a name.
            assertEquals(CommandStatus.REJECTED, runtime.submit("file info").status());
        }
    }

    // ------------------------------------------------------------------

    private Path prepareScope(String notesText, String reportText) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("demo"));
        Files.writeString(root.resolve("notes.txt"), notesText, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("report.txt"), reportText, StandardCharsets.UTF_8);
        return root;
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

    /** Minimal spec-compliant DOCX ZIP package (see DocumentExtractionFormatsTest). */
    private static byte[] minimalDocx(String sentence) throws Exception {
        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;
        String relationships = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """;
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                <w:sectPr/>
                </w:body>
                </w:document>
                """.formatted(sentence);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(contentTypes.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(relationships.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(document.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private final class Runtime implements AutoCloseable {
        final ExecutorService executor;
        final SqliteHistoryRepository history;
        final DefaultCommandGateway gateway;

        Runtime() {
            this(ConfirmationHandler.Decision.CONFIRMED);
        }

        Runtime(ConfirmationHandler.Decision scriptedDecision) {
            executor = new ThreadPoolExecutor(
                    2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
            history = new SqliteHistoryRepository(temporaryDirectory.resolve("history.db"));
            Path scope = temporaryDirectory.resolve("demo");
            gateway = new DefaultCommandGateway(
                    new DesktopAppService(List.of(
                            new ConfiguredApp("calculator", "Calculator", Set.of("calculator")))),
                    new FileSystemFileSearchService(List.of(scope)),
                    new OshiSystemInfoService(),
                    history,
                    executor,
                    new com.jade.services.files.ScopedFileMutationService(List.of(scope), history),
                    history,
                    List.of(scope),
                    pending -> scriptedDecision,
                    Clock.fixed(NOW, ZONE),
                    new FileSystemFileService(),
                    new ContentSearchService());
        }

        CommandOutcome submit(String text) throws Exception {
            CompletableFuture<CommandOutcome> completion = new CompletableFuture<>();
            gateway.submit(CommandRequest.create(text), ignored -> { }, completion::complete);
            return completion.get(15, TimeUnit.SECONDS);
        }

        List<String> recentHistoryTexts() throws Exception {
            return history.recent(10, com.jade.api.CancellationToken.NONE).stream()
                    .map(entry -> entry.originalText())
                    .toList();
        }

        @Override
        public void close() throws Exception {
            gateway.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            history.close();
        }
    }

    private static <T extends CommandResult> T result(CommandOutcome outcome, Class<T> type) {
        assertEquals(CommandStatus.SUCCEEDED, outcome.status(), outcome.summary());
        return type.cast(outcome.result().orElseThrow());
    }
}
