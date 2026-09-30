package com.jade.brain.objective;

import java.util.Arrays;
import java.util.Objects;

/** One causal example; arrays are copied on construction and access. */
public record LanguageModelExample(int[] inputTokenIds, int[] targetTokenIds) {
    public LanguageModelExample {
        Objects.requireNonNull(inputTokenIds, "inputTokenIds");
        Objects.requireNonNull(targetTokenIds, "targetTokenIds");
        if (inputTokenIds.length == 0 || inputTokenIds.length != targetTokenIds.length) {
            throw new IllegalArgumentException("Input and target sequences must be nonempty and equal length");
        }
        inputTokenIds = inputTokenIds.clone();
        targetTokenIds = targetTokenIds.clone();
        for (int id : inputTokenIds) if (id < 0) throw new IllegalArgumentException("Negative input token ID");
        for (int id : targetTokenIds) if (id < 0) throw new IllegalArgumentException("Negative target token ID");
    }

    public static LanguageModelExample fromTokens(int[] tokens) {
        Objects.requireNonNull(tokens, "tokens");
        if (tokens.length < 2) throw new IllegalArgumentException("Next-token prediction requires at least two tokens");
        return new LanguageModelExample(Arrays.copyOf(tokens, tokens.length - 1),
                Arrays.copyOfRange(tokens, 1, tokens.length));
    }

    @Override public int[] inputTokenIds() { return inputTokenIds.clone(); }
    @Override public int[] targetTokenIds() { return targetTokenIds.clone(); }
}
