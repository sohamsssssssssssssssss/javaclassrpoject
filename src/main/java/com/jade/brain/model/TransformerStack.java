package com.jade.brain.model;

import com.jade.brain.math.Matrix;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Ordered immutable block stack; backward consumes caches in reverse layer order. */
public final class TransformerStack {
    private final List<TransformerBlock> blocks;

    public TransformerStack(List<TransformerBlock> blocks) {
        this.blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (this.blocks.isEmpty()) throw new IllegalArgumentException("Transformer stack cannot be empty");
    }

    public static final class ForwardPass {
        private final TransformerStack owner;
        private final List<TransformerBlock.ForwardCache> caches;
        private final Matrix output;
        private ForwardPass(TransformerStack owner, List<TransformerBlock.ForwardCache> caches, Matrix output) {
            this.owner = owner;
            this.caches = List.copyOf(caches);
            this.output = output;
        }
        public Matrix output() { return output; }
        public int depth() { return caches.size(); }
    }

    public ForwardPass forwardCached(Matrix input) {
        Matrix state = Objects.requireNonNull(input, "input");
        var caches = new ArrayList<TransformerBlock.ForwardCache>(blocks.size());
        for (TransformerBlock block : blocks) {
            var cache = block.forwardCached(state);
            caches.add(cache);
            state = cache.output();
        }
        return new ForwardPass(this, caches, state);
    }

    public Matrix forward(Matrix input) { return forwardCached(input).output(); }

    public record BackwardResult(Matrix dInput, List<TransformerBlock.BackwardResult> layers) {
        public BackwardResult {
            Objects.requireNonNull(dInput, "dInput");
            layers = List.copyOf(layers);
            if (layers.isEmpty()) throw new IllegalArgumentException("No layer gradients");
        }
    }

    public BackwardResult backward(ForwardPass pass, Matrix upstream) {
        if (pass == null || pass.owner != this || pass.depth() != blocks.size())
            throw new IllegalArgumentException("Invalid stack forward cache");
        Objects.requireNonNull(upstream, "upstream");
        if (upstream.rows() != pass.output.rows() || upstream.columns() != pass.output.columns())
            throw new IllegalArgumentException("Invalid stack upstream shape");
        var layers = new TransformerBlock.BackwardResult[blocks.size()];
        Matrix gradient = upstream;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            layers[i] = blocks.get(i).backward(pass.caches.get(i), gradient);
            gradient = layers[i].dInput();
        }
        return new BackwardResult(gradient, Arrays.asList(layers));
    }
}
