package com.jade.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record HistoryEntry(
        UUID requestId,
        String originalText,
        CommandStatus status,
        String summary,
        Optional<StructuredError> error,
        Instant submittedAt,
        Instant startedAt,
        Instant completedAt) {
    public HistoryEntry {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(originalText, "originalText");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(summary, "summary");
        error = Objects.requireNonNull(error, "error");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
