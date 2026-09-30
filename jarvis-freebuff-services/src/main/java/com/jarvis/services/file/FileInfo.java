package com.jarvis.services.file;

import java.nio.file.Path;
import java.time.Instant;

public record FileInfo(
        String name,
        Path absolutePath,
        String extension,
        long sizeBytes,
        Instant lastModified,
        boolean directory) {
    public FileInfo {
        absolutePath = absolutePath.normalize();
    }
}