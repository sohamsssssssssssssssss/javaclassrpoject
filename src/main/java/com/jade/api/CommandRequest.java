package com.jade.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CommandRequest(UUID id, String originalText, Instant submittedAt) {
    public CommandRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(originalText, "originalText");
        Objects.requireNonNull(submittedAt, "submittedAt");
    }

    public static CommandRequest create(String originalText) {
        return new CommandRequest(UUID.randomUUID(), originalText, Instant.now());
    }
}
