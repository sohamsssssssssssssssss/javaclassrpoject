package com.jade.brain.training;

import com.jade.brain.objective.LanguageModelExample;
import java.util.AbstractList;
import java.util.List;

/** Partition-local windows materialize only on access; no context/target matrix is retained. */
public final class StoredDataset {
    private StoredDataset() {}
    public static List<TokenDataset.Window> windows(TokenStore store, int context, int stride) {
        if (store == null || context < 1 || context > 4096 || stride < 1 || store.size() < context + 1L
                || store.size() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid token partition/context/stride");
        long count = 1 + (store.size() - context - 1) / stride;
        // ponytail: shuffled order uses bounded int indices; external permutation storage if millions are needed.
        if (count > 1_000_000) throw new IllegalArgumentException("Window index count limit exceeded");
        return new AbstractList<>() {
            public int size() { return (int) count; }
            public TokenDataset.Window get(int index) {
                if (index < 0 || index >= count) throw new IndexOutOfBoundsException("Window index: " + index);
                int start = Math.toIntExact((long) index * stride);
                return new TokenDataset.Window(start, LanguageModelExample.fromTokens(store.range(start, context + 1)));
            }
        };
    }
}
