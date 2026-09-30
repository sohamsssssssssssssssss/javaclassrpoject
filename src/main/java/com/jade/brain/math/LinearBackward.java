package com.jade.brain.math;

import java.util.Objects;

/** Explicit bias-free Y = XW backward, shared by existing projection layers. */
public final class LinearBackward {
    private LinearBackward() { }
    public record Result(Matrix dInput, Matrix dWeights) {
        public Result {
            Objects.requireNonNull(dInput, "dInput");
            Objects.requireNonNull(dWeights, "dWeights");
        }
    }
    public static Result calculate(Matrix input, Matrix weights, Matrix upstream) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(upstream, "upstream");
        if (input.columns() != weights.rows() || input.rows() != upstream.rows()
                || upstream.columns() != weights.columns()) {
            throw new IllegalArgumentException("Invalid linear backward shapes");
        }
        return new Result(upstream.multiply(weights.transpose()), input.transpose().multiply(upstream));
    }
}
