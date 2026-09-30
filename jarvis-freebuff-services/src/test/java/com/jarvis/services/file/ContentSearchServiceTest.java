package com.jarvis.services.file;

import com.jarvis.api.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSearchServiceTest {

    private final ContentSearchService service = new ContentSearchService();

    @Test
    void blankQueryIsRejected(@TempDir Path temp) {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.searchContent(List.of(temp), "   "));
        assertEquals(com.jarvis.api.ErrorCode.INVALID_COMMAND, exception.error().code());
    }

    @Test
    void countsMatchesAndBuildsSnippet(@TempDir Path temp) throws Exception {
        Path hit = temp.resolve("hit.txt");
        Files.writeString(hit, "the needle is here. another needle follows.", StandardCharsets.UTF_8);
        Path miss = temp.resolve("miss.txt");
        Files.writeString(miss, "nothing relevant in this file", StandardCharsets.UTF_8);

        ContentSearchService.SearchResult result = service.searchContent(List.of(hit, miss), "needle");

        assertEquals(2, result.getDocumentResults().size());
        ContentSearchService.DocumentResult hitResult = result.getDocumentResults().get(0);
        assertEquals(2, hitResult.matchCount());
        assertTrue(hitResult.snippet().isPresent());
        assertTrue(hitResult.snippet().get().contains("needle"));
        assertEquals(2, result.totalMatches());

        ContentSearchService.DocumentResult missResult = result.getDocumentResults().get(1);
        assertEquals(0, missResult.matchCount());
        assertTrue(missResult.snippet().isEmpty());
    }

    @Test
    void matchingIsCaseInsensitive(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("case.txt");
        Files.writeString(file, "Say HELLO to JARVIS", StandardCharsets.UTF_8);

        ContentSearchService.SearchResult result = service.searchContent(List.of(file), "hello");

        assertEquals(1, result.totalMatches());
        assertTrue(result.getDocumentResults().get(0).snippet().isPresent());
    }

    @Test
    void failedExtractionYieldsZeroMatches(@TempDir Path temp) throws Exception {
        Path garbage = temp.resolve("broken.pdf");
        Files.write(garbage, new byte[]{0x00, 0x01, 0x02, 0x03});

        ContentSearchService.SearchResult result = service.searchContent(List.of(garbage), "anything");

        assertEquals(0, result.totalMatches());
        assertTrue(result.getDocumentResults().get(0).snippet().isEmpty());
    }

    @Test
    void documentLimitIsAppliedAndReported(@TempDir Path temp) throws Exception {
        java.util.List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            Path file = temp.resolve("doc" + i + ".txt");
            Files.writeString(file, "content " + i, StandardCharsets.UTF_8);
            files.add(file);
        }

        ContentSearchService.SearchResult result = service.searchContent(files, "content");

        assertEquals(100, result.getDocumentResults().size());
        assertEquals("Document limit of 100 applied", result.appliedLimits());
    }
}
