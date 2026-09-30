package com.jarvis.api;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

public record FileSearchQuery(
        Set<String> extensions,
        OptionalLong minimumSizeBytes,
        int maxResults,
        int scanLimit) {
    public FileSearchQuery {
        extensions = Set.copyOf(Objects.requireNonNull(extensions, "extensions"));
        minimumSizeBytes = Objects.requireNonNull(minimumSizeBytes, "minimumSizeBytes");
        if (extensions.isEmpty() || maxResults < 1 || scanLimit < 1) {
            throw new IllegalArgumentException("extensions and positive limits are required");
        }
    }
}
