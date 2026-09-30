package com.jade.brain.model;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import java.util.Random;
import java.util.Objects;

/** Pre-norm residual attention, followed by a bias-free two-layer GELU MLP. */
public final class TransformerBlock {
    static final double EPSILON = 1e-6;
    private final CausalSelfAttention attention;
    private final FeedForwardNetwork ffn;

    public TransformerBlock(BrainConfig config, Random random) {
        attention = new CausalSelfAttention(config, random);
        ffn = new FeedForwardNetwork(Matrix.random(config.embeddingDimension(), config.feedForwardDimension(), random),
                Matrix.random(config.feedForwardDimension(), config.embeddingDimension(), random));
    }

    public Matrix forward(Matrix input) {
        return forwardCached(input).output();
    }

    /** Immutable matrices from one forward call; retained by the stack until its reverse pass. */
    public static final class ForwardCache {
        private final TransformerBlock owner;
        private final Matrix input, attentionInput, residual, ffnInput, output;
        private ForwardCache(TransformerBlock owner, Matrix input, Matrix attentionInput,
                             Matrix residual, Matrix ffnInput, Matrix output) {
            this.owner = owner;
            this.input = input;
            this.attentionInput = attentionInput;
            this.residual = residual;
            this.ffnInput = ffnInput;
            this.output = output;
        }
        public Matrix output() { return output; }
    }
    public ForwardCache forwardCached(Matrix input) {
        Matrix attentionInput = input.rmsNorm(EPSILON);
        Matrix residual = input.add(attention.forward(attentionInput));
        Matrix ffnInput = residual.rmsNorm(EPSILON);
        return new ForwardCache(this, input, attentionInput, residual, ffnInput,
                residual.add(ffn.forward(ffnInput)));
    }

    public TransformerBlock(CausalSelfAttention attention, FeedForwardNetwork ffn) {
        this.attention = Objects.requireNonNull(attention);
        this.ffn = Objects.requireNonNull(ffn);
        if (attention.queryWeights().rows() != ffn.upWeights().rows())
            throw new IllegalArgumentException("Block dimensions differ");
    }
    public CausalSelfAttention attention() { return attention; }
    public record BackwardResult(Matrix dInput, CausalSelfAttention.BackwardResult attention,
                                 Matrix dUp, Matrix dDown) {
        public BackwardResult { Objects.requireNonNull(dInput); Objects.requireNonNull(attention);
            Objects.requireNonNull(dUp); Objects.requireNonNull(dDown); }
    }
    public BackwardResult backward(Matrix input, Matrix upstream) {
        return backward(forwardCached(input), upstream);
    }
    public BackwardResult backward(ForwardCache cache, Matrix upstream) {
        if (cache == null || cache.owner != this) throw new IllegalArgumentException("Invalid block forward cache");
        var tail = backwardFfnResidual(cache.residual, cache.ffnInput, upstream);
        var branch = attention.backward(cache.attentionInput, tail.dAttentionResidual());
        Matrix dInput = tail.dAttentionResidual().add(cache.input.rmsNormBackward(branch.dInput(), EPSILON));
        return new BackwardResult(dInput, branch, tail.dUp(), tail.dDown());
    }

    public FeedForwardNetwork ffn() { return ffn; }
    public Matrix afterAttention(Matrix input) { return input.add(attention.forward(input.rmsNorm(EPSILON))); }
    public Matrix ffnResidualForward(Matrix residual) { return residual.add(ffn.forward(residual.rmsNorm(EPSILON))); }

    /** Stops at attention's residual output; this is NOT the complete block input gradient. */
    public record FfnBoundaryBackward(Matrix dAttentionResidual, Matrix dUp, Matrix dDown) {
        public FfnBoundaryBackward {
            Objects.requireNonNull(dAttentionResidual, "dAttentionResidual");
            Objects.requireNonNull(dUp, "dUp");
            Objects.requireNonNull(dDown, "dDown");
        }
    }
    public FfnBoundaryBackward backwardFfnResidual(Matrix residual, Matrix upstream) {
        return backwardFfnResidual(residual, residual.rmsNorm(EPSILON), upstream);
    }
    private FfnBoundaryBackward backwardFfnResidual(Matrix residual, Matrix normalized, Matrix upstream) {
        var branch = ffn.backward(normalized, upstream);
        Matrix dBranch = residual.rmsNormBackward(branch.dInput(), EPSILON);
        return new FfnBoundaryBackward(upstream.add(dBranch), branch.dUp(), branch.dDown());
    }
}
