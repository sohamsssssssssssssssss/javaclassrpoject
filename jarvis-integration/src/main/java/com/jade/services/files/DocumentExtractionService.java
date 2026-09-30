package com.jade.services.files;

import com.jade.api.ServiceException;
import com.jade.api.StructuredError;
import com.jade.api.ErrorCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

public final class DocumentExtractionService {

    private static final long MAX_FILE_SIZE = 10_000_000L; // 10MB
    private final Tika tika = new Tika();

    public DocumentText extract(Path file) {
        java.util.Objects.requireNonNull(file, "file");

        // Validate file existence
        if (!java.nio.file.Files.exists(file)) {
            return new DocumentText(
                    file,
                    "unknown",
                    "",
                    ExtractionStatus.FAILURE,
                    Optional.of(new StructuredError(
                            ErrorCode.INVALID_COMMAND,
                            "File not found: " + file,
                            Optional.empty())));

        }

        // Validate it's a regular file (not directory)
        if (!java.nio.file.Files.isRegularFile(file)) {
            return new DocumentText(
                    file,
                    "unknown",
                    "",
                    ExtractionStatus.UNSUPPORTED_FORMAT,
                    Optional.of(new StructuredError(
                            ErrorCode.INVALID_COMMAND,
                            "Path is not a regular file: " + file,
                            Optional.empty())));

        }

        // Validate file size
        long fileSize;
        try {
            fileSize = java.nio.file.Files.size(file);
        } catch (IOException e) {
            return new DocumentText(
                    file,
                    "unknown",
                    "",
                    ExtractionStatus.FAILURE,
                    Optional.of(new StructuredError(
                            ErrorCode.IO_FAILURE,
                            "Could not read file size: " + file,
                            Optional.empty())));

        }

        if (fileSize > MAX_FILE_SIZE) {
            return new DocumentText(
                    file,
                    "unknown",
                    "",
                    ExtractionStatus.FAILURE,
                    Optional.of(new StructuredError(
                            ErrorCode.INVALID_COMMAND,
                            "File size exceeds maximum allowed size: " + fileSize,
                            Optional.empty())));

        }

        // Reject empty files before parsing: there is nothing to extract.
        if (fileSize == 0) {
            return new DocumentText(
                    file,
                    guessMediaType(file),
                    "",
                    ExtractionStatus.FAILURE,
                    Optional.of(new StructuredError(
                            ErrorCode.IO_FAILURE,
                            "File is empty: " + file,
                            Optional.empty())));
        }

        // Try to extract text using Tika
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            String text = tika.parseToString(fis);

            if (text == null || text.trim().isEmpty()) {
                return new DocumentText(
                        file,
                        guessMediaType(file),
                        "",
                        ExtractionStatus.FAILURE,
                        Optional.of(new StructuredError(
                                ErrorCode.IO_FAILURE,
                                "No text could be extracted from: " + file,
                                Optional.empty())));

            }

            // Bound extracted text size
            String boundedText = (text.length() > 100_000) ? text.substring(0, 100_000) : text;

            return new DocumentText(
                    file,
                    guessMediaType(file),
                    boundedText,
                    ExtractionStatus.SUCCESS,
                    Optional.empty());

        } catch (TikaException e) {
            return new DocumentText(
                    file,
                    guessMediaType(file),
                    "",
                    ExtractionStatus.UNSUPPORTED_FORMAT,
                    Optional.of(new StructuredError(
                            ErrorCode.INVALID_COMMAND,
                            "Unsupported format or corrupted document: " + file,
                            Optional.empty())));

        } catch (IOException e) {
            return new DocumentText(
                    file,
                    guessMediaType(file),
                    "",
                    ExtractionStatus.FAILURE,
                    Optional.of(new StructuredError(
                            ErrorCode.IO_FAILURE,
                            "Failed to read document: " + file,
                            Optional.empty())));

        }
    }

    private String guessMediaType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }
}
