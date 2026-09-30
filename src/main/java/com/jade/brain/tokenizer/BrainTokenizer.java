package com.jade.brain.tokenizer;

/** Text/token contract; concrete tokenizers define their Unicode and resource policies. */
public interface BrainTokenizer {
    int[] encode(String text);
    String decode(int[] tokenIds);
    int vocabularySize();
}
