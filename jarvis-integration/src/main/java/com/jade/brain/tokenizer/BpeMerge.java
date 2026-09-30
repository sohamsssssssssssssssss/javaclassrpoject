package com.jade.brain.tokenizer;

public record BpeMerge(int leftTokenId, int rightTokenId, int resultTokenId, int rank) {
    public static final int MAX_MERGES = 8192;
    public BpeMerge {
        for (int id : new int[] {leftTokenId, rightTokenId, resultTokenId}) {
            if (id < 0 || id >= BpeVocabulary.MAX_VOCABULARY_SIZE) throw new IllegalArgumentException("Invalid merge ID");
        }
        if (rank < 0 || rank >= MAX_MERGES) throw new IllegalArgumentException("Invalid merge rank");
    }
    static long pairKey(int left, int right) { return ((long) left << 32) | (right & 0xffffffffL); }
    long pairKey() { return pairKey(leftTokenId, rightTokenId); }
}
