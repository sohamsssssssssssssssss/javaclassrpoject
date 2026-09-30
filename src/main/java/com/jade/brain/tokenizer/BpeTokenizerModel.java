package com.jade.brain.tokenizer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Validated immutable vocabulary and rank-ordered rules. */
public record BpeTokenizerModel(BpeVocabulary vocabulary, List<BpeMerge> merges) {
    public BpeTokenizerModel {
        Objects.requireNonNull(vocabulary, "vocabulary");
        if (merges.size() > BpeMerge.MAX_MERGES) throw new IllegalArgumentException("Too many merges");
        merges = List.copyOf(merges).stream().sorted(Comparator.comparingInt(BpeMerge::rank)).toList();
        var pairs = new HashSet<Long>();
        var ranks = new HashSet<Integer>();
        var available = new HashSet<Integer>();
        for (int id = 0; id < BpeVocabulary.BASE_SIZE; id++) available.add(id);
        for (BpeMerge merge : merges) {
            if (!pairs.add(merge.pairKey()) || !ranks.add(merge.rank())) {
                throw new IllegalArgumentException("Duplicate merge pair or rank");
            }
            if (!available.contains(merge.leftTokenId()) || !available.contains(merge.rightTokenId())) {
                throw new IllegalArgumentException("Merge refers to a token not established by an earlier rank");
            }
            byte[] joined = concatenate(vocabulary.token(merge.leftTokenId()).bytes(),
                    vocabulary.token(merge.rightTokenId()).bytes());
            if (!Arrays.equals(joined, vocabulary.token(merge.resultTokenId()).bytes())) {
                throw new IllegalArgumentException("Merge result must equal concatenated input bytes");
            }
            available.add(merge.resultTokenId());
        }
        if (available.size() != vocabulary.size()) throw new IllegalArgumentException("Unreachable merged vocabulary token");
    }

    static byte[] concatenate(byte[] left, byte[] right) {
        if (left.length + right.length > BpeVocabulary.MAX_TOKEN_BYTES) {
            throw new IllegalArgumentException("Merged token byte limit exceeded");
        }
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return joined;
    }
    public int baseVocabularySize() { return BpeVocabulary.BASE_SIZE; }
    public int vocabularySize() { return vocabulary.size(); }
    public int mergeCount() { return merges.size(); }
}
