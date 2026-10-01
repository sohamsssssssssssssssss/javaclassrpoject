package com.jade.brain.tokenizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned, SHA-256-checked interchange for JADE's existing byte BPE. */
public final class TokenizerArtifact {
    private TokenizerArtifact() {}
    public record Loaded(BpeTokenizerModel model, String corpusDigest, String digest) {}

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void hex(String value) {
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected lowercase SHA-256");
    }
    public static String save(BpeTokenizerModel model, String corpusDigest, Path path) throws IOException {
        Objects.requireNonNull(model); hex(corpusDigest);
        var body = new StringBuilder("JADE-BPE-1\n");
        body.append("vocab=").append(model.vocabularySize()).append('\n');
        body.append("corpus=").append(corpusDigest).append('\n');
        body.append("merges=").append(model.mergeCount()).append('\n');
        for (var merge : model.merges()) body.append(merge.leftTokenId()).append(' ')
                .append(merge.rightTokenId()).append(' ').append(merge.resultTokenId()).append('\n');
        String digest = sha(body.toString().getBytes(StandardCharsets.US_ASCII));
        Path target = path.toAbsolutePath(), temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.createDirectories(target.getParent());
        try {
            Files.writeString(temp, body + "sha256=" + digest + "\n", StandardCharsets.US_ASCII);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        return digest;
    }
    public static Loaded load(Path path) throws IOException {
        if (Files.size(path) > 131072) throw new IOException("Tokenizer artifact too large");
        String text = Files.readString(path, StandardCharsets.US_ASCII);
        int last = text.lastIndexOf("sha256=");
        if (last < 0 || !text.endsWith("\n")) throw new IOException("Missing tokenizer digest");
        String body = text.substring(0, last), digest = text.substring(last + 7, text.length() - 1);
        if (!digest.equals(sha(body.getBytes(StandardCharsets.US_ASCII)))) throw new IOException("Tokenizer digest mismatch");
        String[] lines = body.split("\n");
        try {
            if (lines.length < 4 || !lines[0].equals("JADE-BPE-1")) throw new IllegalArgumentException();
            int vocab = Integer.parseInt(lines[1].replaceFirst("^vocab=", ""));
            String corpus = lines[2].replaceFirst("^corpus=", ""); hex(corpus);
            int count = Integer.parseInt(lines[3].replaceFirst("^merges=", ""));
            if (!lines[1].startsWith("vocab=") || !lines[2].startsWith("corpus=") ||
                    !lines[3].startsWith("merges=") || count != lines.length - 4 ||
                    vocab != BpeVocabulary.BASE_SIZE + count || vocab > BpeVocabulary.MAX_VOCABULARY_SIZE)
                throw new IllegalArgumentException();
            var tokens = BpeVocabulary.baseTokens(); var merges = new ArrayList<BpeMerge>();
            for (int rank = 0; rank < count; rank++) {
                String[] ids = lines[4 + rank].split(" ");
                if (ids.length != 3) throw new IllegalArgumentException();
                int left = Integer.parseInt(ids[0]), right = Integer.parseInt(ids[1]), result = Integer.parseInt(ids[2]);
                if (left < 0 || right < 0 || left >= tokens.size() || right >= tokens.size() || result != tokens.size())
                    throw new IllegalArgumentException();
                tokens.add(new BpeToken(result, BpeTokenizerModel.concatenate(tokens.get(left).bytes(), tokens.get(right).bytes())));
                merges.add(new BpeMerge(left, right, result, rank));
            }
            return new Loaded(new BpeTokenizerModel(new BpeVocabulary(tokens), merges), corpus, digest);
        } catch (RuntimeException invalid) { throw new IOException("Invalid tokenizer artifact", invalid); }
    }
    /** Migration of the existing Loop 6D chat merge list; refuses non-sequential or duplicate token bytes. */
    public static BpeTokenizerModel fromLegacyPairs(Path path) throws IOException {
        var lines = Files.readAllLines(path, StandardCharsets.US_ASCII);
        if (lines.size() != 768) throw new IOException("Expected the verified 1024-token merge list");
        var tokens = BpeVocabulary.baseTokens(); var merges = new ArrayList<BpeMerge>();
        try {
            for (int rank = 0; rank < lines.size(); rank++) {
                String[] ids = lines.get(rank).split(" ");
                if (ids.length != 2) throw new IllegalArgumentException();
                int left = Integer.parseInt(ids[0]), right = Integer.parseInt(ids[1]);
                if (left < 0 || right < 0 || left >= tokens.size() || right >= tokens.size()) throw new IllegalArgumentException();
                int result = tokens.size();
                tokens.add(new BpeToken(result, BpeTokenizerModel.concatenate(tokens.get(left).bytes(), tokens.get(right).bytes())));
                merges.add(new BpeMerge(left, right, result, rank));
            }
            return new BpeTokenizerModel(new BpeVocabulary(tokens), merges);
        } catch (RuntimeException invalid) { throw new IOException("Legacy merge list does not define unique sequential tokens", invalid); }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("legacy-merges corpus-sha artifact golden-tsv");
        var model = fromLegacyPairs(Path.of(args[0]));
        String digest = save(model, args[1], Path.of(args[2]));
        var loaded = load(Path.of(args[2]));
        var tokenizer = new ByteBpeTokenizer(loaded.model());
        var texts = List.of("hello", "hello jade", "my name is", "what is a computer", "what is java",
                "the sky is blue", "once upon a time", "Hello, world!", "Numbers: 12, 2026; 3.14.",
                "don't stop", "first line\nsecond line", "caf\u00e9 na\u00efve");
        var golden = new StringBuilder("escaped_text\ttoken_ids\n");
        for (String text : texts) {
            int[] ids = tokenizer.encode(text);
            if (!tokenizer.decode(ids).equals(text)) throw new IOException("Tokenizer round-trip failed");
            golden.append(text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t"))
                    .append('\t').append(Arrays.toString(ids).replace("[", "").replace("]", "").replace(", ", ","))
                    .append('\n');
        }
        Files.writeString(Path.of(args[3]), golden.toString(), StandardCharsets.UTF_8);
        System.out.println("TOKENIZER vocab=" + model.vocabularySize() + " digest=" + digest + " corpus=" + args[1]);
    }
}
