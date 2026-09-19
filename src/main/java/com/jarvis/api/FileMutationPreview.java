package com.jarvis.api;

import java.util.Objects;

/**
 * Result of a mutation command that stored its pending confirmation for the
 * typed follow-up flow ("confirm" / "cancel"). The preview is the exact
 * typed {@link PendingConfirmation} — carrying the concrete source/target
 * paths resolved at planning time — so a later {@code confirm} executes
 * precisely the previewed operations without reparsing any sentence.
 */
public record FileMutationPreview(PendingConfirmation pending) implements CommandResult {
    public FileMutationPreview {
        Objects.requireNonNull(pending, "pending");
    }
}
