package com.jarvis.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One recorded, reversible file operation.
 *
 * <p>Rows persist in the {@code undo_entries} table and capture enough
 * before/after state to invert the operation (move: swap source and target;
 * rename: same). Copy and folder creation are deliberately <b>not</b> undone
 * per docs/PROJECT_DECISIONS.md — JARVIS never fabricates an undo that does
 * not really reverse the user's visible result.</p>
 */
public record UndoEntry(
        UUID id,
        UUID requestId,
        MutationKind kind,
        Path source,
        Path target,
        Instant recordedAt) {

    /** Lifecycle of a journal row, tracked per row so undo is idempotent. */
    public enum JournalStatus {
        ACTIVE,
        UNDONE,
        FAILED
    }

    public UndoEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    public record JournalRow(UndoEntry entry, JournalStatus status, Optional<StructuredError> error) {
        public JournalRow {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(status, "status");
            error = Objects.requireNonNull(error, "error");
        }
    }
}
