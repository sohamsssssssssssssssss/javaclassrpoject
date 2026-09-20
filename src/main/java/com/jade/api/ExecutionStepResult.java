package com.jade.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed outcome of one planned step: which step, how it ended, how long it
 * took, and the structured result it produced (present only when the step
 * succeeded). Bounded by construction — steps return typed values, never
 * logs.
 */
public record ExecutionStepResult(
        PlanStep step,
        StepStatus status,
        long durationMillis,
        Optional<CommandResult> result) {
    public ExecutionStepResult {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(status, "status");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        result = Objects.requireNonNull(result, "result");
    }

    /** Bounded per-step outcome categories. */
    public enum StepStatus {
        SUCCEEDED,
        /** The step ran and reported an honest non-success (e.g. BUILD_FAILED). */
        FAILED_RESULT,
        /** The step could not run at all (infrastructure/state failure). */
        ERROR,
        /** The step was skipped because an earlier step blocked it. */
        SKIPPED
    }
}
