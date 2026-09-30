package com.jade.api;

import java.util.Objects;
import java.util.Optional;

/**
 * One TODO/FIXME marker found by static source inspection: its location and
 * a bounded one-line snippet. Binaries are never scanned.
 */
public record TodoFinding(String marker, String path, int lineNumber, Optional<String> snippet) {
    public TodoFinding {
        marker = Objects.requireNonNull(marker, "marker");
        path = Objects.requireNonNull(path, "path");
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be 1-based");
        }
        snippet = Objects.requireNonNull(snippet, "snippet");
    }
}
