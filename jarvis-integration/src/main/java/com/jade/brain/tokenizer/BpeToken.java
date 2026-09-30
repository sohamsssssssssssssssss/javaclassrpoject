package com.jade.brain.tokenizer;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Byte sequences, not display strings, are canonical. */
public final class BpeToken {
    private final int id;
    private final byte[] bytes;

    public BpeToken(int id, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (id < 0 || id >= BpeVocabulary.MAX_VOCABULARY_SIZE || bytes.length == 0
                || bytes.length > BpeVocabulary.MAX_TOKEN_BYTES) {
            throw new IllegalArgumentException("Invalid BPE token ID or byte length");
        }
        this.id = id;
        this.bytes = bytes.clone();
    }

    public int id() { return id; }
    public byte[] bytes() { return bytes.clone(); }
    public int byteLength() { return bytes.length; }
    @Override public boolean equals(Object other) {
        return other instanceof BpeToken token && id == token.id && Arrays.equals(bytes, token.bytes);
    }
    @Override public int hashCode() { return 31 * id + Arrays.hashCode(bytes); }
    @Override public String toString() { return id + ":0x" + HexFormat.of().formatHex(bytes); }
}
