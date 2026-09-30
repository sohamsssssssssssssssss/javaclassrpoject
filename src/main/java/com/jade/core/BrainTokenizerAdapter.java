package com.jade.core;

import com.jade.brain.model.BrainVocabulary;
import com.jade.brain.tokenizer.BrainTokenizer;
import java.util.Objects;

/** Integration glue: the existing lexer owns token boundaries; Brain owns only fixed IDs. */
public final class BrainTokenizerAdapter implements BrainTokenizer {
    private final BrainVocabulary vocabulary;
    private final CommandTokenizer tokenizer = new CommandTokenizer();

    public BrainTokenizerAdapter(BrainVocabulary vocabulary) {
        this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary");
    }

    @Override public int vocabularySize() { return vocabulary.size(); }
    @Override public String decode(int[] tokenIds) { return vocabulary.decode(tokenIds); }

    public int[] encode(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > 8192) throw new IllegalArgumentException("Brain V0 text limit exceeded");
        try {
            var tokens = tokenizer.tokenize(text);
            int[] ids = new int[tokens.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = vocabulary.id(tokens.get(i).value());
            return ids;
        } catch (CommandParseException invalid) {
            throw new IllegalArgumentException("Invalid lexical input: " + invalid.getMessage(), invalid);
        }
    }
}
