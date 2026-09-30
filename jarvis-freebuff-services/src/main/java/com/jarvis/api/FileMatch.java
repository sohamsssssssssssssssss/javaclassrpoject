package com.jarvis.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record FileMatch(Path path, String fileName, long sizeBytes, Instant modifiedAt) {
    public FileMatch {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
