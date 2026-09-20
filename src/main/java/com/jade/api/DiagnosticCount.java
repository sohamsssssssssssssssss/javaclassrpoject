package com.jade.api;

import java.util.Objects;

/**
 * Compact answer to "how many tests failed": the number of test-level
 * problems detected in the most recent project operation, plus that
 * operation's status for honest context.
 */
public record DiagnosticCount(long failedTestCount, ProjectOperationStatus lastStatus)
        implements CommandResult {
    public DiagnosticCount {
        if (failedTestCount < 0) {
            throw new IllegalArgumentException("failedTestCount must not be negative");
        }
        Objects.requireNonNull(lastStatus, "lastStatus");
    }
}
