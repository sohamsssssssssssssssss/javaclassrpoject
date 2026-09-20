package com.jade.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CommandOutcome(
        UUID requestId,
        CommandStatus status,
        String summary,
        Optional<CommandResult> result,
        Optional<StructuredError> error,
        Instant startedAt,
        Instant completedAt) {
    public CommandOutcome {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(summary, "summary");
        result = Objects.requireNonNull(result, "result");
        error = Objects.requireNonNull(error, "error");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
