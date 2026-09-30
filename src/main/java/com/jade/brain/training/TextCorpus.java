package com.jade.brain.training;

import com.jade.brain.tokenizer.BrainTokenizer;
import com.jade.brain.tokenizer.ByteBpeTokenizer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded, deterministic local UTF-8 ingestion; no discovery, network or implicit normalization. */
public record TextCorpus(int[] tokenIds, int fileCount, long utf8Bytes, long codePoints) {
    public TextCorpus {
        tokenIds = tokenIds.clone();
        if (fileCount < 1 || utf8Bytes < 1 || codePoints < 1 || tokenIds.length < 2)
            throw new IllegalArgumentException("Corpus must contain at least two tokens");
    }
    @Override public int[] tokenIds() { return tokenIds.clone(); }
    public int tokenCount() { return tokenIds.length; }

    public static TextCorpus load(List<Path> files, BrainTokenizer tokenizer,
                                  int maxBytes, int maxTokens) throws IOException {
        Objects.requireNonNull(files, "files"); Objects.requireNonNull(tokenizer, "tokenizer");
        if (files.isEmpty() || maxBytes < 1 || maxBytes > ByteBpeTokenizer.MAX_INPUT_BYTES
                || maxTokens < 2 || maxTokens > ByteBpeTokenizer.MAX_INPUT_BYTES)
            throw new IllegalArgumentException("Invalid corpus limits or file list");
        List<Path> sorted = files.stream().map(p -> Objects.requireNonNull(p).toAbsolutePath().normalize())
                .sorted(Comparator.comparing(Path::toString)).toList();
        if (sorted.stream().distinct().count() != sorted.size())
            throw new IllegalArgumentException("Duplicate corpus file");
        var text = new StringBuilder(); long bytes = 0, points = 0;
        for (Path path : sorted) {
            if (!path.getFileName().toString().endsWith(".txt") || !Files.isRegularFile(path)
                    || !Files.isReadable(path)) throw new IOException("Not a readable .txt file: " + path);
            long size = Files.size(path);
            if (size > maxBytes - bytes) throw new IllegalArgumentException("Corpus byte limit exceeded");
            byte[] data;
            try (var input = Files.newInputStream(path)) {
                data = input.readNBytes((int) (maxBytes - bytes + 1));
            }
            if (data.length > maxBytes - bytes) throw new IllegalArgumentException("Corpus byte limit exceeded");
            String part;
            try {
                part = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data)).toString();
            } catch (CharacterCodingException invalid) {
                throw new IOException("Invalid UTF-8 in " + path, invalid);
            }
            text.append(part); bytes += data.length;
            points += part.codePointCount(0, part.length());
        }
        if (bytes == 0 || points == 0) throw new IllegalArgumentException("Empty corpus");
        int[] ids = tokenizer.encode(text.toString());
        if (ids.length > maxTokens) throw new IllegalArgumentException("Corpus token limit exceeded");
        return new TextCorpus(ids, sorted.size(), bytes, points);
    }
}
