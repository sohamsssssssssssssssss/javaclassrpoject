package com.jarvis.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Human-readable preview of a planned mutation, shown before anything on disk
 * changes (master plan §29: JARVIS proves it understood the command before
 * modifying anything).
 */
public record PendingConfirmation(
        String originalCommand,
        MutationKind kind,
        RiskLevel riskLevel,
        String destinationDescription,
        List<PlannedFile> plannedFiles) {
    public PendingConfirmation {
        Objects.requireNonNull(originalCommand, "originalCommand");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(destinationDescription, "destinationDescription");
        plannedFiles = List.copyOf(Objects.requireNonNull(plannedFiles, "plannedFiles"));
    }

    public record PlannedFile(Path source, Path target) {
        public PlannedFile {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
        }
    }
}
