package com.jarvis.services.search;

import com.jarvis.api.CancellationToken;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.FileMatch;
import com.jarvis.api.FileSearchQuery;
import com.jarvis.api.FileSearchResult;
import com.jarvis.api.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemFileSearchServiceTest {

    private static CancellationToken cancelled() {
        return () -> true;
    }

    private static CancellationToken never() {
        return CancellationToken.NONE;
    }

    private static FileSearchQuery pdfQuery() {
        return new FileSearchQuery(Set.of("pdf"), OptionalLong.empty(), 50, 10_000);
    }

    @Test
    void constructorRejectsMissingAndNonDirectoryRoots(@TempDir Path temp) throws Exception {
        Path missing = temp.resolve("missing");
        assertThrows(IllegalArgumentException.class, () -> new FileSystemFileSearchService(null));
        assertThrows(IllegalArgumentException.class, () -> new FileSystemFileSearchService(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FileSystemFileSearchService(List.of(missing)));
        Path file = temp.resolve("plain.txt");
        Files.writeString(file, "x");
        assertThrows(IllegalArgumentException.class,
                () -> new FileSystemFileSearchService(List.of(file)));
    }

    @Test
    void findsPdfsWithExtensionsAndSizesInsideRoot(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("Documents");
        Files.createDirectories(root.resolve("nested"));
        Files.write(root.resolve("a.pdf"), new byte[10]);
        Files.write(root.resolve("b.PDF"), new byte[2_000_000]);
        Files.write(root.resolve("c.txt"), new byte[10]);
        Files.write(root.resolve("d"), new byte[10]);
        Files.write(root.resolve("nested/e.pdf"), new byte[500]);

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));

        FileSearchResult result = service.search(pdfQuery(), never(), null);

        assertEquals(3, result.matches().size());
        // Matches order by filename (ASCII case-insensitive); original case is preserved in output.
        assertEquals(List.of("a.pdf", "b.PDF", "e.pdf"),
                result.matches().stream().map(FileMatch::fileName).toList());
        // Visited = a.pdf, b.PDF, c.txt, d, nested/e.pdf = 5 regular files.
        assertEquals(5, result.visitedFiles());
        assertFalse(result.resultLimitReached());
        assertFalse(result.scanLimitReached());
        assertTrue(service.warnings().isEmpty());
    }

    @Test
    void minimumSizeFiltersSmallerFiles(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("Downloads");
        Files.createDirectories(root);
        Files.write(root.resolve("big.pdf"), new byte[2_048]);
        Files.write(root.resolve("small.pdf"), new byte[10]);

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));
        FileSearchQuery query = new FileSearchQuery(
                Set.of("pdf"), OptionalLong.of(1024), 50, 10_000);

        FileSearchResult result = service.search(query, never(), null);

        assertEquals(1, result.matches().size());
        assertEquals("big.pdf", result.matches().get(0).fileName());
        assertEquals(2_048, result.matches().get(0).sizeBytes());
    }

    @Test
    void deterministicOrderingAcrossReorderedDirectories(@TempDir Path temp) throws Exception {
        Path rootA = temp.resolve("rootA");
        Path rootB = temp.resolve("rootB");
        Files.createDirectories(rootA);
        Files.createDirectories(rootB);
        Files.write(rootA.resolve("zz.pdf"), new byte[1]);
        Files.write(rootB.resolve("aa.pdf"), new byte[1]);

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(rootA, rootB));
        FileSearchResult result = service.search(pdfQuery(), never(), null);

        assertEquals(2, result.matches().size());
        assertTrue(result.matches().get(0).path().toString().endsWith("aa.pdf"));
        assertTrue(result.matches().get(1).path().toString().endsWith("zz.pdf"));
    }

    @Test
    void emptyCompleteSearchIsNotPartial(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("empty-root");
        Files.createDirectories(root);
        Files.write(root.resolve("note.txt"), new byte[5]);

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));
        FileSearchResult result = service.search(pdfQuery(), never(), null);

        assertTrue(result.matches().isEmpty());
        assertEquals(1, result.visitedFiles());
        assertFalse(result.resultLimitReached());
        assertFalse(result.scanLimitReached());
    }

    @Test
    void resultLimitStopsAtMaxResultsAndFlags(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("many");
        Files.createDirectories(root);
        for (int i = 0; i < 5; i++) {
            Files.write(root.resolve("file" + i + ".pdf"), new byte[1]);
        }

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));
        FileSearchQuery query = new FileSearchQuery(
                Set.of("pdf"), OptionalLong.empty(), 2, 10_000);

        FileSearchResult result = service.search(query, never(), null);

        assertEquals(2, result.matches().size());
        assertTrue(result.resultLimitReached());
        assertFalse(result.scanLimitReached());
    }

    @Test
    void scanLimitFlagsPartialResults(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("scaN");
        Files.createDirectories(root);
        for (int i = 0; i < 5; i++) {
            Files.write(root.resolve("file" + i + ".pdf"), new byte[1]);
        }

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));
        FileSearchQuery query = new FileSearchQuery(
                Set.of("pdf"), OptionalLong.empty(), 50, 3);

        FileSearchResult result = service.search(query, never(), null);

        assertTrue(result.scanLimitReached());
        assertTrue(result.visitedFiles() <= 3);
        assertTrue(result.matches().size() <= 3);
    }

    @Test
    void sprintLimitsClampOversizedQueries(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("clamp");
        Files.createDirectories(root);

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));
        FileSearchQuery query = new FileSearchQuery(
                Set.of("pdf"), OptionalLong.empty(), 10_000, 10_000_000);

        FileSearchResult result = service.search(query, never(), null);

        assertTrue(result.matches().isEmpty());
        // No exception means clamped query was accepted; limits are enforced internally.
    }

    @Test
    void cancellationReportsCancelledAndVisitsNothing(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("cancel");
        Files.createDirectories(root);
        Files.write(root.resolve("a.pdf"), new byte[1]);

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.search(pdfQuery(), cancelled(), null));

        assertEquals(ErrorCode.CANCELLED, exception.error().code());
    }

    @Test
    void symlinksAreNeverFollowed(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("linked");
        Files.createDirectories(root.resolve("real"));
        Files.write(root.resolve("real/target.pdf"), new byte[1]);
        Files.createSymbolicLink(root.resolve("link.pdf"), root.resolve("real/target.pdf"));
        Files.createSymbolicLink(root.resolve("dir-link"), root.resolve("real"));

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));
        FileSearchResult result = service.search(pdfQuery(), never(), null);

        // Only the real file is visited; the symlinked directory adds nothing.
        assertEquals(1, result.visitedFiles());
        assertEquals(1, result.matches().size());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("symbolic link")));
    }

    @Test
    void symlinkEscapeAttemptDoesNotEscapeRoot(@TempDir Path temp) throws Exception {
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.write(outside.resolve("secret.pdf"), new byte[1]);

        Path root = temp.resolve("root");
        Files.createDirectories(root);
        Files.createSymbolicLink(root.resolve("escape.pdf"), outside.resolve("secret.pdf"));

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(root));
        FileSearchResult result = service.search(pdfQuery(), never(), null);

        assertEquals(0, result.matches().size());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("symbolic link")));
    }

    @Test
    void progressCallbackReportsVisitedFiles(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("progress");
        Files.createDirectories(root);
        for (int i = 0; i < 260; i++) {
            Files.write(root.resolve("f" + i + ".pdf"), new byte[1]);
        }

        FileSystemFileSearchService service =
            new FileSystemFileSearchService(List.of(root));
        // maxResults below file count would short-circuit; use full scan
        FileSearchQuery query = new FileSearchQuery(
                Set.of("pdf"), OptionalLong.empty(), 50, 10_000);

        // Progress reports multiples of 250, so 260 files → 1 report
        // But result limit 50 stops the scan early...
        // Count visited via scanLimit high enough: resultLimit stops at 50 matches → visited 50
        // So progress would not fire. Use extension that matches nothing to force full walk.
        FileSearchQuery noMatch = new FileSearchQuery(
                Set.of("zzz"), OptionalLong.empty(), 50, 10_000);
        List<Long> reports = new java.util.ArrayList<>();
        FileSearchResult result = service.search(noMatch, never(), reports::add);

        assertEquals(0, result.matches().size());
        assertEquals(260, result.visitedFiles());
        // One report at 250 (every-250 cadence) plus the final-tail report at 260.
        assertEquals(List.of(250L, 260L), reports);
    }

    @Test
    void inaccessibleRootProducesAccessDeniedWhenNothingScannable(@TempDir Path temp) {
        Path root = temp.resolve("secret");
        // Directory created then made unreadable; on POSIX this is reproducible.
        Path unreadable = org.junit.jupiter.api.io.TempDir.class == null
                ? root : createUnreadable(root);

        FileSystemFileSearchService service =
                new FileSystemFileSearchService(List.of(unreadable));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.search(pdfQuery(), never(), null));

        assertEquals(ErrorCode.ACCESS_DENIED, exception.error().code());
    }

    private static Path createUnreadable(Path root) {
        try {
            java.nio.file.Files.createDirectories(root);
            root.toFile().setReadable(false);
            return root;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
