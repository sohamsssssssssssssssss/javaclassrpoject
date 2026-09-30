package com.jade.brain.tokenizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Tokenizer vocabulary learning only. No neural parameters or personal data are involved. */
public final class BpeTrainer {
    public static final int MAX_CORPUS_BYTES = 65_536;
    public static final int MAX_CORPUS_ENTRIES = 4096;
    public static final long MAX_TRAINING_PAIR_SCANS = 128_000_000;

    public BpeTokenizerModel train(Collection<String> corpus, int targetVocabularySize) {
        Objects.requireNonNull(corpus, "corpus");
        if (targetVocabularySize < BpeVocabulary.BASE_SIZE || targetVocabularySize > BpeVocabulary.MAX_VOCABULARY_SIZE
                || corpus.size() > MAX_CORPUS_ENTRIES) {
            throw new IllegalArgumentException("Invalid training target or corpus entry count");
        }
        List<int[]> sequences = new ArrayList<>();
        int corpusBytes = 0;
        for (String text : corpus) {
            byte[] bytes = ByteBpeTokenizer.utf8(text);
            corpusBytes += bytes.length;
            if (corpusBytes > MAX_CORPUS_BYTES) throw new IllegalArgumentException("Corpus byte limit exceeded");
            sequences.add(ByteBpeTokenizer.baseIds(bytes));
        }
        List<BpeToken> tokens = BpeVocabulary.baseTokens();
        List<BpeMerge> merges = new ArrayList<>();
        Map<Long, BpeMerge> rules = new HashMap<>();
        Map<ByteBuffer, Integer> known = new HashMap<>();
        for (BpeToken token : tokens) known.put(BpeVocabulary.key(token.bytes()), token.id());
        Comparator<Map.Entry<Long, Integer>> order = Comparator
                .<Map.Entry<Long, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparingLong(Map.Entry::getKey);
        // Fitting scans the corpus for many merges; encoding keeps its smaller per-input budget.
        long[] remainingWork = {MAX_TRAINING_PAIR_SCANS};
        long vocabularyBytes = BpeVocabulary.BASE_SIZE;
        // ponytail: count/replace full sequences; bounded fixtures first, incremental counts when profiling warrants it.
        while (tokens.size() < targetVocabularySize) {
            var counts = new HashMap<Long, Integer>();
            for (int[] sequence : sequences) {
                remainingWork[0] -= Math.max(0, sequence.length - 1);
                if (remainingWork[0] < 0) throw new IllegalArgumentException("Training work limit exceeded");
                for (int i = 0; i + 1 < sequence.length; i++) {
                    counts.merge(BpeMerge.pairKey(sequence[i], sequence[i + 1]), 1, Integer::sum);
                }
            }
            if (counts.isEmpty()) break;
            if (merges.size() == BpeMerge.MAX_MERGES) throw new IllegalArgumentException("Training merge limit exceeded");
            long pair = counts.entrySet().stream().min(order).orElseThrow().getKey();
            int left = (int) (pair >>> 32), right = (int) pair;
            byte[] joined = BpeTokenizerModel.concatenate(tokens.get(left).bytes(), tokens.get(right).bytes());
            ByteBuffer key = BpeVocabulary.key(joined);
            Integer result = known.get(key);
            if (result == null) {
                result = tokens.size();
                vocabularyBytes += joined.length;
                if (vocabularyBytes > BpeVocabulary.MAX_VOCABULARY_BYTES) throw new IllegalArgumentException("Vocabulary byte limit exceeded");
                tokens.add(new BpeToken(result, joined));
                known.put(key, result);
            }
            var merge = new BpeMerge(left, right, result, merges.size());
            merges.add(merge);
            rules.put(merge.pairKey(), merge);
            for (int i = 0; i < sequences.size(); i++) {
                int[] replaced = ByteBpeTokenizer.replace(sequences.get(i), merge);
                // Alternate decompositions reuse a byte-identical token; reapply earlier rules if exposed.
                sequences.set(i, ByteBpeTokenizer.applyRanks(replaced, rules, remainingWork));
            }
        }
        return new BpeTokenizerModel(new BpeVocabulary(tokens), merges);
    }
}
