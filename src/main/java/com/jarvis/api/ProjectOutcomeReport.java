package com.jarvis.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Answer to "what happened": the most recent project operation of this
 * session, or an explicitly empty report when no project operation has run
 * yet. Bounded to project operations — not a general conversation history.
 */
public record ProjectOutcomeReport(Optional<ProjectOperationResult> operation) implements CommandResult {
    public ProjectOutcomeReport {
        operation = Objects.requireNonNull(operation, "operation");
    }

    public static ProjectOutcomeReport none() {
        return new ProjectOutcomeReport(Optional.empty());
    }

    public static ProjectOutcomeReport of(ProjectOperationResult result) {
        return new ProjectOutcomeReport(Optional.of(Objects.requireNonNull(result, "result")));
    }
}
