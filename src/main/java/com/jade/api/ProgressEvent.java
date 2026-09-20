package com.jade.api;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

public record ProgressEvent(
        UUID requestId,
        ProgressStage stage,
        String message,
        long completedUnits,
        OptionalLong totalUnits,
        Instant occurredAt) {
    public ProgressEvent {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(message, "message");
        totalUnits = Objects.requireNonNull(totalUnits, "totalUnits");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
