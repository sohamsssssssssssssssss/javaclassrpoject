package com.jade.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Result of undoing one request's recorded file operations. */
public record UndoResult(UUID undoneRequestId, List<UndoEntry.JournalRow> rows) implements CommandResult {
    public UndoResult {
        Objects.requireNonNull(undoneRequestId, "undoneRequestId");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("at least one journal row is required");
        }
    }

    public boolean fullyReversed() {
        return rows.stream().allMatch(row -> row.status() == UndoEntry.JournalStatus.UNDONE);
    }
}
