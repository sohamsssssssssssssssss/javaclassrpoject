package com.jade.brain.model;

import com.jade.brain.math.Matrix;
import com.jade.brain.math.LinearBackward;
import java.util.Objects;

/** Bias-free immutable output projection: [T,D] x [D,V] -> [T,V]. */
public final class LmHead {
    private final Matrix weights;

    public LmHead(Matrix weights) { this.weights = Objects.requireNonNull(weights, "weights"); }
    public Matrix weights() { return weights; }
    public Matrix forward(Matrix hidden) { return hidden.multiply(weights); }

    public LmHeadBackwardResult backward(Matrix hidden, Matrix dLogits) {
        Objects.requireNonNull(hidden, "hidden");
        Objects.requireNonNull(dLogits, "dLogits");
        if (hidden.columns() != weights.rows() || hidden.rows() != dLogits.rows()
                || dLogits.columns() != weights.columns()) {
            throw new IllegalArgumentException("Invalid LM-head backward shapes");
        }
        var gradients = LinearBackward.calculate(hidden, weights, dLogits);
        return new LmHeadBackwardResult(gradients.dInput(), gradients.dWeights());
    }
}
