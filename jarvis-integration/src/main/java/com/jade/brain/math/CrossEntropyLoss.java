package com.jade.brain.math;

import java.util.Objects;

/** Categorical negative log likelihood, in natural-log units (nats). */
public final class CrossEntropyLoss {
    private CrossEntropyLoss() { }

    public static double at(double[] logits, int target) {
        Objects.requireNonNull(logits, "logits");
        if (target < 0 || target >= logits.length) throw new IllegalArgumentException("Invalid target class");
        double max = -Double.MAX_VALUE;
        for (double logit : logits) {
            if (!Double.isFinite(logit)) throw new IllegalArgumentException("Non-finite logit");
            max = Math.max(max, logit);
        }
        double sum = 0;
        for (double logit : logits) sum += Math.exp(logit - max);
        // Algebraically logSumExp(z) - z[target]; subtract first to avoid cancelling huge offsets.
        double loss = (max - logits[target]) + Math.log(sum);
        if (!Double.isFinite(loss)) throw new IllegalArgumentException("Loss exceeds finite double range");
        return loss;
    }

    /** Arithmetic MEAN over positions, not sum; validates the configured vocabulary width. */
    public static double mean(double[][] logits, int[] targets, int vocabularySize) {
        Objects.requireNonNull(logits, "logits");
        Objects.requireNonNull(targets, "targets");
        if (targets.length == 0 || logits.length != targets.length || vocabularySize < 1) {
            throw new IllegalArgumentException("Invalid sequence loss shape or vocabulary size");
        }
        double mean = 0;
        for (int t = 0; t < targets.length; t++) {
            if (logits[t] == null || logits[t].length != vocabularySize) {
                throw new IllegalArgumentException("Logit row must match configured vocabulary size");
            }
            double loss = at(logits[t], targets[t]);
            // Online arithmetic mean avoids overflow from summing many large finite losses.
            mean += (loss - mean) / (t + 1);
        }
        return mean;
    }

    /** Explicit backward for the same MEAN objective; no caller-owned data is changed. */
    public static CrossEntropyResult meanWithGradient(double[][] logits, int[] targets, int vocabularySize) {
        double loss = mean(logits, targets, vocabularySize); // Reuse all forward validation and numerics.
        double[][] gradient = new double[targets.length][];
        for (int t = 0; t < targets.length; t++) {
            gradient[t] = Matrix.softmax(logits[t]);
            gradient[t][targets[t]] -= 1;
            for (int i = 0; i < vocabularySize; i++) gradient[t][i] /= targets.length;
        }
        return new CrossEntropyResult(loss, new Matrix(gradient));
    }
}
