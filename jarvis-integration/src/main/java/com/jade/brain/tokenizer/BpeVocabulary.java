package com.jade.brain.tokenizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

public final class BpeVocabulary {
    public static final int BASE_SIZE = 256;
    public static final int MAX_VOCABULARY_SIZE = 4096;
    public static final int MAX_TOKEN_BYTES = 4096;
    public static final int MAX_VOCABULARY_BYTES = 1_048_576;
    private final List<BpeToken> tokens;
    private final Map<ByteBuffer, Integer> ids;

    public BpeVocabulary(List<BpeToken> tokens) {
        if (tokens.size() < BASE_SIZE || tokens.size() > MAX_VOCABULARY_SIZE) {
            throw new IllegalArgumentException("BPE vocabulary must contain 256..4096 tokens");
        }
        this.tokens = List.copyOf(tokens);
        var lookup = new HashMap<ByteBuffer, Integer>();
        long totalBytes = 0;
        for (int id = 0; id < this.tokens.size(); id++) {
            BpeToken token = this.tokens.get(id);
            byte[] bytes = token.bytes();
            if (token.id() != id || (id < BASE_SIZE && (bytes.length != 1 || Byte.toUnsignedInt(bytes[0]) != id))) {
                throw new IllegalArgumentException("BPE IDs must be contiguous with exact base byte mapping");
            }
            if (lookup.put(key(bytes), id) != null) throw new IllegalArgumentException("Duplicate token bytes");
            totalBytes += bytes.length;
            if (totalBytes > MAX_VOCABULARY_BYTES) throw new IllegalArgumentException("Vocabulary byte limit exceeded");
        }
        ids = Map.copyOf(lookup);
    }

    static ByteBuffer key(byte[] bytes) { return ByteBuffer.wrap(bytes.clone()).asReadOnlyBuffer(); }
    static List<BpeToken> baseTokens() {
        var tokens = new ArrayList<BpeToken>();
        for (int id = 0; id < BASE_SIZE; id++) tokens.add(new BpeToken(id, new byte[] {(byte) id}));
        return tokens;
    }
    public static BpeVocabulary base() { return new BpeVocabulary(baseTokens()); }
    public int size() { return tokens.size(); }
    public List<BpeToken> tokens() { return tokens; }
    public BpeToken token(int id) {
        if (id < 0 || id >= size()) throw new IllegalArgumentException("Invalid BPE token ID");
        return tokens.get(id);
    }
    public OptionalInt tokenId(byte[] bytes) {
        Integer id = ids.get(key(bytes));
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }
}
