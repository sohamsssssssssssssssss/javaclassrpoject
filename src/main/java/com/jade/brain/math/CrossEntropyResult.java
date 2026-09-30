package com.jade.brain.math;

import java.util.Objects;

/** Immutable mean loss and its derivative with respect to each position's logits. */
public record CrossEntropyResult(double loss, Matrix dLogits) {
    public CrossEntropyResult {
        if (!Double.isFinite(loss) || loss < 0) throw new IllegalArgumentException("Invalid cross-entropy loss");
        Objects.requireNonNull(dLogits, "dLogits");
    }
}
