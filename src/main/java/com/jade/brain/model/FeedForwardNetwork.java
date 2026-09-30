package com.jade.brain.model;

import com.jade.brain.math.LinearBackward;
import com.jade.brain.math.Matrix;
import java.util.Objects;

/** The existing bias-free D -> F -> D tanh-GELU network, with explicit backward. */
public final class FeedForwardNetwork {
    private final Matrix up, down;
    public FeedForwardNetwork(Matrix up, Matrix down) {
        this.up = Objects.requireNonNull(up, "up");
        this.down = Objects.requireNonNull(down, "down");
        if (up.columns() != down.rows() || up.rows() != down.columns()) {
            throw new IllegalArgumentException("FFN must map D -> F -> D");
        }
    }
    public Matrix upWeights() { return up; }
    public Matrix downWeights() { return down; }
    public Matrix forward(Matrix input) { return input.multiply(up).gelu().multiply(down); }
    public record BackwardResult(Matrix dInput, Matrix dUp, Matrix dDown) {
        public BackwardResult {
            Objects.requireNonNull(dInput, "dInput");
            Objects.requireNonNull(dUp, "dUp");
            Objects.requireNonNull(dDown, "dDown");
        }
    }
    public BackwardResult backward(Matrix input, Matrix upstream) {
        Matrix preActivation = input.multiply(up);
        var second = LinearBackward.calculate(preActivation.gelu(), down, upstream);
        Matrix dPreActivation = preActivation.geluBackward(second.dInput());
        var first = LinearBackward.calculate(input, up, dPreActivation);
        return new BackwardResult(first.dInput(), first.dWeights(), second.dWeights());
    }
}
