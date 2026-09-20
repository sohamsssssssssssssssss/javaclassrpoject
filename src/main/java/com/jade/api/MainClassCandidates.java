package com.jade.api;

import java.util.List;
import java.util.Objects;

/**
 * Static main-class detection result: {@code public static void main}
 * signatures found in the project's Java sources. Zero, one and multiple
 * candidates are all legitimate answers — the service never guesses which
 * one is "the" entry point when several exist.
 */
public record MainClassCandidates(List<Candidate> candidates) implements CommandResult {
    public MainClassCandidates {
        candidates = List.copyOf(candidates);
    }

    public record Candidate(String className, String signature, String path) {
        public Candidate {
            Objects.requireNonNull(className, "className");
            signature = Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(path, "path");
        }
    }
}
