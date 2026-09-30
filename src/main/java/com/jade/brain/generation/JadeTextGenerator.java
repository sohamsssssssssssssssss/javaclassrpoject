package com.jade.brain.generation;

import com.jade.brain.tokenizer.BrainTokenizer;
import com.jade.brain.model.JadeLanguageModel;
import java.util.Arrays;
import java.util.Objects;

/** In-process autoregression. No EOS, sliding window, sampling, or external runtime. */
public final class JadeTextGenerator {
    private final JadeLanguageModel model;
    private final BrainTokenizer tokenizer;
    private final TokenSelector selector;

    public JadeTextGenerator(JadeLanguageModel model, BrainTokenizer tokenizer, TokenSelector selector) {
        this.model = Objects.requireNonNull(model, "model");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.selector = Objects.requireNonNull(selector, "selector");
        if (model.config().vocabSize() != tokenizer.vocabularySize()) {
            throw new IllegalArgumentException("Model and vocabulary sizes differ");
        }
    }

    public GenerationResult generate(int[] promptTokenIds, int maxNewTokens) {
        Objects.requireNonNull(promptTokenIds, "promptTokenIds");
        if (maxNewTokens < 0) throw new IllegalArgumentException("Negative token budget");
        int limit = model.config().contextLength();
        if (promptTokenIds.length == 0 || promptTokenIds.length > limit) {
            throw new IllegalArgumentException("Prompt must fit the nonempty context window");
        }
        for (int id : promptTokenIds) validateId(id);
        int[] tokens = promptTokenIds.clone();
        int generated = 0;
        // ponytail: recompute the entire prefix each step; KV caching is deferred until needed.
        while (generated < maxNewTokens && tokens.length < limit) {
            int next = selector.select(model.nextTokenLogits(tokens));
            validateId(next); // Validate IDs without decoding potentially incomplete UTF-8 bytes.
            tokens = Arrays.copyOf(tokens, tokens.length + 1);
            tokens[tokens.length - 1] = next;
            generated++;
        }
        int[] newIds = Arrays.copyOfRange(tokens, promptTokenIds.length, tokens.length);
        // When both limits are reached together, the requested token budget takes precedence.
        var reason = generated == maxNewTokens ? GenerationResult.StopReason.MAX_NEW_TOKENS
                : GenerationResult.StopReason.CONTEXT_LIMIT;
        return new GenerationResult(Arrays.stream(promptTokenIds).boxed().toList(),
                Arrays.stream(newIds).boxed().toList(), tokenizer.decode(newIds), reason);
    }

    private void validateId(int id) {
        if (id < 0 || id >= tokenizer.vocabularySize()) throw new IllegalArgumentException("Invalid tokenizer ID");
    }
}
