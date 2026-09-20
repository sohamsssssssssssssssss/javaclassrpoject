package com.jade.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Result of a contextual selection command such as "open the newest": the
 * one file deterministically chosen from the current session result set.
 * The {@code path} is always the concrete absolute path of the selected
 * file at selection time.
 */
public record SelectedFileResult(Path path, String fileName, long sizeBytes, Instant modifiedAt)
        implements CommandResult {
    public SelectedFileResult {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
