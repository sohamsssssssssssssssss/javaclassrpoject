package com.jade.api;

/**
 * The bounded set of typed steps the deterministic planner may sequence.
 * A plan is exactly a sequence of these values — the planner cannot invent
 * arguments, commands, or steps outside this enum.
 */
public enum PlanStep {
    /** Static inspection of the active project. */
    INSPECT_PROJECT,
    /** Run the typed TEST operation on the active project. */
    RUN_TESTS,
    /** Run the typed BUILD operation on the active project. */
    RUN_BUILD,
    /** Structured diagnostics of the most recent project operation. */
    DIAGNOSTICS,
    /** The bounded outcome report of the most recent project operation. */
    LAST_PROJECT_OUTCOME
}
