package com.jarvis.services.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionServiceTest {

    private final DocumentExtractionService service = new DocumentExtractionService();

    @Test
    void extractsPlainTextSuccessfully(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("note.txt");
        Files.writeString(file, "hello jarvis", StandardCharsets.UTF_8);

        DocumentText result = service.extract(file);

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals("text/plain", result.mediaType());
        assertTrue(result.text().contains("hello jarvis"));
        assertTrue(result.error().isEmpty());
        assertEquals(file, result.path());
    }

    @Test
    void missingFileReturnsFailure(@TempDir Path temp) {
        Path missing = temp.resolve("does-not-exist.txt");

        DocumentText result = service.extract(missing);

        assertEquals(ExtractionStatus.FAILURE, result.status());
        assertEquals("", result.text());
        assertTrue(result.error().isPresent());
        assertTrue(result.error().get().message().contains("File not found"));
    }

    @Test
    void directoryIsRejectedAsUnsupported(@TempDir Path temp) throws Exception {
        Path dir = temp.resolve("folder");
        Files.createDirectories(dir);

        DocumentText result = service.extract(dir);

        assertEquals(ExtractionStatus.UNSUPPORTED_FORMAT, result.status());
        assertTrue(result.error().isPresent());
        assertTrue(result.error().get().message().contains("not a regular file"));
    }

    @Test
    void emptyFileYieldsFailureWithNoText(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("empty.txt");
        Files.writeString(file, "");

        DocumentText result = service.extract(file);

        assertEquals(ExtractionStatus.FAILURE, result.status());
        assertEquals("", result.text());
        assertTrue(result.error().isPresent());
    }

    @Test
    void oversizedFileIsRejectedWithoutReading(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("huge.txt");
        Files.write(file, new byte[10_000_001]); // one byte over the 10 MB cap

        DocumentText result = service.extract(file);

        assertEquals(ExtractionStatus.FAILURE, result.status());
        assertEquals("", result.text());
        assertTrue(result.error().get().message().contains("maximum allowed size"));
    }

    @Test
    void extractedTextIsBounded(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("long.txt");
        Files.writeString(file, "a".repeat(150_000));

        DocumentText result = service.extract(file);

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(100_000, result.text().length());
    }
}
