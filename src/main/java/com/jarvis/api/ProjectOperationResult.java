package com.jarvis.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Structured result of one executed project operation: what ran, on which
 * project, how it ended, how long it took, and a bounded, truthfully
 * truncated output summary. Rendered directly by the UI; never reduced to a
 * single human string internally.
 */
public record ProjectOperationResult(
        ProjectOperation operation,
        String projectName,
        Path projectRoot,
        ProjectOperationStatus status,
        Integer exitCode,
        long durationMillis,
        String outputSummary,
        boolean outputTruncated,
        boolean timedOut) implements CommandResult {
    public ProjectOperationResult {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(projectName, "projectName");
        projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(status, "status");
        outputSummary = Objects.requireNonNull(outputSummary, "outputSummary");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
    }
}
