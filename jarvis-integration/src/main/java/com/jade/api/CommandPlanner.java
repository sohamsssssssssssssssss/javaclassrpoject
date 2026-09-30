package com.jade.api;

/**
 * Deterministic planner for a small set of explicitly supported compound
 * requests. Implementations map bounded phrases to an
 * {@link ExecutionPlan}; arbitrary natural-language decomposition is
 * refused — an unrecognised request yields {@code Optional.empty()}, which
 * the caller treats as "not a compound command".
 */
@FunctionalInterface
public interface CommandPlanner {

    /**
     * Plans the given request text.
     *
     * @return the typed plan, or empty when the text is not a supported
     *         compound request (the caller then parses it normally).
     */
    java.util.Optional<ExecutionPlan> plan(String request);
}
