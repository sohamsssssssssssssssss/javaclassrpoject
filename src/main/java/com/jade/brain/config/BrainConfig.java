package com.jade.brain.config;

/** Configuration for the small, CPU-only experimental forward pass. */
public record BrainConfig(int vocabSize, int contextLength, int embeddingDimension,
                          int numberOfHeads, int numberOfLayers, int feedForwardDimension) {
    public BrainConfig {
        if (vocabSize < 1 || vocabSize > 65_536 || contextLength < 1 || contextLength > 4096
                || embeddingDimension < 1 || embeddingDimension > 4096
                || numberOfHeads < 1 || numberOfHeads > embeddingDimension
                || numberOfLayers < 1 || numberOfLayers > 64
                || feedForwardDimension < 1 || feedForwardDimension > 4096) {
            throw new IllegalArgumentException("Invalid or unsupported Brain V0 dimensions");
        }
        if (embeddingDimension % numberOfHeads != 0) {
            throw new IllegalArgumentException("Embedding dimension must be divisible by head count");
        }
        // Loop 5D readiness ceiling; larger models require a separate memory/performance gate.
        long parameters = exactParameterCount(vocabSize, contextLength, embeddingDimension, numberOfLayers, feedForwardDimension);
        if (parameters > 5_500_000 || (long) contextLength * feedForwardDimension > 1_000_000
                || (long) contextLength * embeddingDimension > 1_000_000
                || (long) contextLength * vocabSize > 2_000_000) {
            throw new IllegalArgumentException("Brain V0 allocation limit exceeded");
        }
    }

    /** Bias-free projections and unit-scale RMSNorm have no additional parameters. */
    public long parameterCount() {
        return exactParameterCount(vocabSize, contextLength, embeddingDimension, numberOfLayers, feedForwardDimension);
    }

    public static long exactParameterCount(int vocabulary, int context, int width, int layers, int ffn) {
        if (vocabulary < 1 || context < 1 || width < 1 || layers < 1 || ffn < 1)
            throw new IllegalArgumentException("Parameter dimensions must be positive");
        try {
            long embeddings = Math.multiplyExact(2L * vocabulary + context, width);
            long block = Math.addExact(Math.multiplyExact(4L * width, width), Math.multiplyExact(2L * width, ffn));
            return Math.addExact(embeddings, Math.multiplyExact(block, layers));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Parameter count overflow", overflow);
        }
    }
}
