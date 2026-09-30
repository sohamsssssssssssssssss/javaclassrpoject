package com.jade.brain.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed case-sensitive lexical vocabulary for V0; not a trained subword tokenizer. */
public final class BrainVocabulary {
    public static final int UNKNOWN_ID = 0;
    private final List<String> tokens;
    private final Map<String, Integer> ids;

    public BrainVocabulary(List<String> vocabulary) {
        Objects.requireNonNull(vocabulary, "vocabulary");
        if (vocabulary.size() > 65_535) throw new IllegalArgumentException("Vocabulary exceeds V0 limit");
        var entries = new ArrayList<String>();
        var mapping = new HashMap<String, Integer>();
        entries.add("<unk>");
        mapping.put("<unk>", UNKNOWN_ID);
        for (String token : vocabulary) {
            if (token == null || token.isBlank() || mapping.containsKey(token)) {
                throw new IllegalArgumentException("Vocabulary tokens must be nonblank and unique");
            }
            mapping.put(token, entries.size());
            entries.add(token);
        }
        tokens = List.copyOf(entries);
        ids = Map.copyOf(mapping);
    }

    public int size() { return tokens.size(); }
    public int id(String token) { return ids.getOrDefault(Objects.requireNonNull(token), UNKNOWN_ID); }
    public String token(int id) {
        if (id < 0 || id >= size()) throw new IllegalArgumentException("Invalid vocabulary ID");
        return tokens.get(id);
    }

    /** Display decoding only: spaces separate lexical entries; original whitespace/quotes are lost. */
    public String decode(int[] tokenIds) {
        Objects.requireNonNull(tokenIds, "tokenIds");
        var text = new StringBuilder();
        for (int id : tokenIds) {
            if (!text.isEmpty()) text.append(' ');
            text.append(token(id));
        }
        return text.toString();
    }
}
