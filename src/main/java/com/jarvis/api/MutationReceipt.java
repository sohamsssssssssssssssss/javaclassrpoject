package com.jarvis.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Receipt for a completed or partially completed file mutation.
 *
 * <p>Each entry mirrors one planned file operation. A failed entry carries a
 * structured error and never blocks the remaining planned operations: the
 * receipt records exactly what happened per file so the user is never given a
 * blanket success for a partially applied request (docs rule 20: never
 * fabricate successful actions).</p>
 */
public record MutationReceipt(MutationKind kind, List<Entry> entries) implements CommandResult {
    public MutationReceipt {
        Objects.requireNonNull(kind, "kind");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("at least one entry is required");
        }
    }

    public record Entry(
            OperationStatus status,
            Path source,
            Path target,
            RiskLevel risk,
            Optional<StructuredError> error) {
        public Entry {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(risk, "risk");
            error = Objects.requireNonNull(error, "error");
        }
    }
}
