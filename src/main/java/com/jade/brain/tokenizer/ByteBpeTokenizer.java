package com.jade.brain.tokenizer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Strict UTF-8 byte BPE. No normalization, unknown token, or special-token protocol. */
public final class ByteBpeTokenizer implements BrainTokenizer {
    public static final int MAX_INPUT_BYTES = 1_048_576;
    public static final long MAX_PAIR_SCANS = 32_000_000;
    private final BpeTokenizerModel model;
    private final Map<Long, BpeMerge> pairs;

    public ByteBpeTokenizer(BpeTokenizerModel model) {
        this.model = Objects.requireNonNull(model, "model");
        var lookup = new HashMap<Long, BpeMerge>();
        for (BpeMerge merge : model.merges()) lookup.put(merge.pairKey(), merge);
        pairs = Map.copyOf(lookup);
    }
    public BpeTokenizerModel model() { return model; }
    @Override public int vocabularySize() { return model.vocabularySize(); }

    static byte[] utf8(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_INPUT_BYTES) throw new IllegalArgumentException("Text size limit exceeded");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (encoded.remaining() > MAX_INPUT_BYTES) throw new IllegalArgumentException("UTF-8 byte limit exceeded");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Text contains malformed Unicode (isolated surrogate)", malformed);
        }
    }
    static int[] baseIds(byte[] bytes) {
        int[] ids = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) ids[i] = Byte.toUnsignedInt(bytes[i]);
        return ids;
    }

    @Override public int[] encode(String text) {
        return applyRanks(baseIds(utf8(text)), pairs);
    }

    static int[] applyRanks(int[] ids, Map<Long, BpeMerge> pairs) {
        return applyRanks(ids, pairs, new long[] {MAX_PAIR_SCANS});
    }

    static int[] applyRanks(int[] ids, Map<Long, BpeMerge> pairs, long[] remainingWork) {
        // ponytail: bounded full scans keep rank semantics clear; use an adjacency heap if scale demands it.
        while (ids.length > 1 && !pairs.isEmpty()) {
            remainingWork[0] -= ids.length - 1;
            if (remainingWork[0] < 0) throw new IllegalArgumentException("BPE work limit exceeded");
            BpeMerge selected = null;
            for (int i = 0; i + 1 < ids.length; i++) {
                BpeMerge merge = pairs.get(BpeMerge.pairKey(ids[i], ids[i + 1]));
                if (merge != null && (selected == null || merge.rank() < selected.rank())) selected = merge;
            }
            if (selected == null) break;
            ids = replace(ids, selected);
        }
        return ids;
    }

    /** All occurrences of the selected pair are replaced left-to-right, without overlap. */
    static int[] replace(int[] ids, BpeMerge merge) {
        int[] output = new int[ids.length];
        int count = 0;
        for (int i = 0; i < ids.length; i++) {
            if (i + 1 < ids.length && ids[i] == merge.leftTokenId() && ids[i + 1] == merge.rightTokenId()) {
                output[count++] = merge.resultTokenId();
                i++;
            } else output[count++] = ids[i];
        }
        return Arrays.copyOf(output, count);
    }

    @Override public String decode(int[] tokenIds) {
        Objects.requireNonNull(tokenIds, "tokenIds");
        if (tokenIds.length > MAX_INPUT_BYTES) throw new IllegalArgumentException("Token count limit exceeded");
        long size = 0;
        for (int id : tokenIds) {
            size += model.vocabulary().token(id).byteLength();
            if (size > MAX_INPUT_BYTES) throw new IllegalArgumentException("Decoded byte limit exceeded");
        }
        var bytes = new ByteArrayOutputStream((int) size);
        for (int id : tokenIds) bytes.writeBytes(model.vocabulary().token(id).bytes());
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Token bytes do not form valid UTF-8", malformed);
        }
    }
}
