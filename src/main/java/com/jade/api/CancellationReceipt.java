package com.jade.api;

import java.util.Objects;

/**
 * Result of cancelling a pending operation through the typed follow-up
 * ("cancel"): nothing was changed on disk and the pending confirmation is
 * cleared.
 */
public record CancellationReceipt(String message) implements CommandResult {
    public CancellationReceipt {
        Objects.requireNonNull(message, "message");
    }
}
