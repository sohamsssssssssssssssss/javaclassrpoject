package com.jade.brain.generation;

import java.util.Objects;

@FunctionalInterface
public interface TokenSelector {
    int select(double[] logits);

    /** Exact ties select the lowest ID. No softmax is needed for argmax. */
    static TokenSelector greedy() {
        return logits -> {
            Objects.requireNonNull(logits, "logits");
            if (logits.length == 0) throw new IllegalArgumentException("Empty logits");
            int best = 0;
            for (int i = 0; i < logits.length; i++) {
                if (!Double.isFinite(logits[i])) throw new IllegalArgumentException("Non-finite logits");
                if (logits[i] > logits[best]) best = i;
            }
            return best;
        };
    }
}
