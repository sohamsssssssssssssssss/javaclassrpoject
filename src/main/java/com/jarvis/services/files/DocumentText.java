package com.jarvis.services.files;

import com.jarvis.api.StructuredError;
import java.nio.file.Path;
import java.util.Optional;

public record DocumentText(
        Path path,
        String mediaType,
        String text,
        ExtractionStatus status,
        Optional<StructuredError> error) {
    public DocumentText {
        path = java.util.Objects.requireNonNull(path, "path");
        mediaType = java.util.Objects.requireNonNull(mediaType, "mediaType");
        text = java.util.Objects.requireNonNull(text, "text");
        status = java.util.Objects.requireNonNull(status, "status");
        error = java.util.Objects.requireNonNull(error, "error");
    }
}
