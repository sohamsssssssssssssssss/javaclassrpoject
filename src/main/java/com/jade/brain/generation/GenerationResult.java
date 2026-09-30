package com.jade.brain.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable generation evidence; generatedText excludes the prompt. */
public record GenerationResult(List<Integer> promptTokenIds, List<Integer> generatedTokenIds,
                               String generatedText, StopReason stopReason) {
    public enum StopReason { MAX_NEW_TOKENS, CONTEXT_LIMIT }

    public GenerationResult {
        promptTokenIds = List.copyOf(promptTokenIds);
        generatedTokenIds = List.copyOf(generatedTokenIds);
        Objects.requireNonNull(generatedText, "generatedText");
        Objects.requireNonNull(stopReason, "stopReason");
    }

    public List<Integer> allTokenIds() {
        var all = new ArrayList<>(promptTokenIds);
        all.addAll(generatedTokenIds);
        return List.copyOf(all);
    }
}
