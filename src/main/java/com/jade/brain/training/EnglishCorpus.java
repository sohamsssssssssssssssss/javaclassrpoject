package com.jade.brain.training;

import com.jade.brain.tokenizer.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Current policy: original/local English prose. Heuristics reject garbage, not identify languages. */
public final class EnglishCorpus {
    private EnglishCorpus() {}
    public record Limits(int maxFileBytes, int maxCorpusBytes, int maxLineCharacters,
                         int maxFiles, int maxBpeFitBytes, int maxTokensPerPartition) {
        public Limits {
            if (maxFileBytes < 1 || maxFileBytes > 16 * 1024 * 1024 || maxCorpusBytes < maxFileBytes
                    || maxCorpusBytes > 64 * 1024 * 1024 || maxLineCharacters < 1 || maxLineCharacters > 16384
                    || maxFiles < 3 || maxFiles > 512 || maxBpeFitBytes < 1 || maxBpeFitBytes > BpeTrainer.MAX_CORPUS_BYTES
                    || maxTokensPerPartition < 1 || maxTokensPerPartition > TokenStore.Chunked.MAX_TOKENS)
                throw new IllegalArgumentException("Invalid English corpus limits");
        }
    }
    public record Entry(String path, long bytes, long codePoints, String digest) {}
    public record Skipped(String path, String reason) {}
    public record Manifest(Path root, List<Entry> files, List<Skipped> skipped,
                           long bytes, long codePoints, String digest) {
        public Manifest { files = List.copyOf(files); skipped = List.copyOf(skipped); }
    }
    /** Whole-file sequential split: floor(train*N), floor(validation*N), each partition at least one file. */
    public record Split(List<Entry> train, List<Entry> validation, List<Entry> test,
                        double trainFraction, double validationFraction) {
        public Split { train = List.copyOf(train); validation = List.copyOf(validation); test = List.copyOf(test); }
    }
    public record Encoded(TokenStore train, TokenStore validation, TokenStore test,
                          long trainBytes, long validationBytes, long testBytes, String identity) {
        public long tokens() { return Math.addExact(Math.addExact(train.size(), validation.size()), test.size()); }
        public CorpusTrainer.Data dataset(int context, int stride) {
            return new CorpusTrainer.Data(StoredDataset.windows(train, context, stride),
                    StoredDataset.windows(validation, context, stride), identity,
                    Math.addExact(Math.addExact(trainBytes, validationBytes), testBytes), tokens());
        }
    }

    public static Manifest scan(Path root, Limits limits) throws IOException {
        root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Corpus root must be a local directory: " + root);
        List<Path> paths;
        try (var walk = Files.walk(root, 8)) {
            paths = walk.limit(8L * limits.maxFiles() + 1).toList();
        }
        if (paths.size() > 8L * limits.maxFiles()) throw new IllegalArgumentException("Corpus directory entry limit exceeded");
        for (Path path : paths) if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && root.relativize(path).getNameCount() >= 8)
            throw new IllegalArgumentException("Corpus directory depth limit exceeded at " + relative(root, path));
        final Path base = root;
        paths = paths.stream().filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(p -> relative(base, p))).toList();
        if (paths.size() > limits.maxFiles()) throw new IllegalArgumentException("Corpus file count limit exceeded");
        var accepted = new ArrayList<Entry>(); var skipped = new ArrayList<Skipped>();
        long bytes = 0, points = 0, scanned = 0;
        for (Path path : paths) {
            String name = relative(root, path);
            if (!name.endsWith(".txt") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                skipped.add(new Skipped(name, "unsupported file or symbolic link")); continue;
            }
            if (!Files.isReadable(path)) { skipped.add(new Skipped(name, "unreadable")); continue; }
            long size = Files.size(path);
            if (size > limits.maxFileBytes() || size > limits.maxCorpusBytes() - scanned)
                throw new IllegalArgumentException("Corpus byte limit exceeded at " + name);
            try {
                var stats = read(path, limits, (int) (limits.maxCorpusBytes() - scanned), line -> {});
                scanned += stats.bytes();
                String reason = stats.rejection();
                if (reason != null) skipped.add(new Skipped(name, reason));
                else {
                    accepted.add(new Entry(name, stats.bytes(), stats.points(), stats.digest()));
                    bytes += stats.bytes(); points += stats.points();
                }
            } catch (CharacterCodingException invalid) {
                scanned += size; skipped.add(new Skipped(name, "invalid UTF-8"));
            }
        }
        if (accepted.isEmpty()) throw new IllegalArgumentException("No accepted English prose files");
        var hash = hash();
        for (Entry entry : accepted) update(hash, entry.path() + "\0" + entry.bytes() + "\0" + entry.codePoints() + "\0" + entry.digest() + "\n");
        return new Manifest(root, accepted, skipped, bytes, points, HexFormat.of().formatHex(hash.digest()));
    }

    public static Split split(Manifest manifest, double trainFraction, double validationFraction) {
        if (!Double.isFinite(trainFraction) || !Double.isFinite(validationFraction) || trainFraction <= 0
                || validationFraction <= 0 || trainFraction + validationFraction >= 1 || manifest.files().size() < 3)
            throw new IllegalArgumentException("Split requires three accepted files and positive train/validation/test fractions");
        int total = manifest.files().size();
        int train = Math.max(1, Math.min(total - 2, (int) Math.floor(total * trainFraction)));
        int val = Math.max(1, Math.min(total - train - 1, (int) Math.floor(total * validationFraction)));
        return new Split(manifest.files().subList(0, train), manifest.files().subList(train, train + val),
                manifest.files().subList(train + val, total), trainFraction, validationFraction);
    }

    /** Fitting is intentionally a bounded training-only sample; never includes held-out text. */
    public static List<String> fittingText(Manifest manifest, Split split, Limits limits) throws IOException {
        validateSplit(manifest, split);
        var sample = new ArrayList<String>(); int[] bytes = {0}; boolean[] full = {false};
        for (Entry file : split.train()) {
            if (full[0]) break;
            verifiedRead(manifest, file, limits, line -> {
                if (full[0]) return;
                int size = line.getBytes(StandardCharsets.UTF_8).length;
                if (size > limits.maxBpeFitBytes() - bytes[0] || sample.size() == BpeTrainer.MAX_CORPUS_ENTRIES) {
                    full[0] = true; return;
                }
                sample.add(line); bytes[0] += size;
            });
        }
        if (sample.isEmpty()) throw new IllegalArgumentException("No complete training line fits BPE sample budget");
        return List.copyOf(sample);
    }

    /** Equal per-document fitting budgets keep a large first document from dominating BPE statistics. */
    public static List<String> balancedFittingText(Manifest manifest, Split split, Limits limits) throws IOException {
        validateSplit(manifest, split);
        var sample = new ArrayList<String>();
        int byteQuota = limits.maxBpeFitBytes() / split.train().size();
        int entryQuota = BpeTrainer.MAX_CORPUS_ENTRIES / split.train().size();
        for (Entry file : split.train()) {
            int[] used = {0,0};
            verifiedRead(manifest, file, limits, line -> {
                int bytes = line.getBytes(StandardCharsets.UTF_8).length;
                if (bytes <= byteQuota - used[0] && used[1] < entryQuota) {
                    sample.add(line); used[0] += bytes; used[1]++;
                }
            });
        }
        if (sample.isEmpty()) throw new IllegalArgumentException("No training lines fit balanced BPE budget");
        return List.copyOf(sample);
    }

    /** BPE rank application resets at each line, including its newline; exact decoded text is preserved. */
    public static Encoded encode(Manifest manifest, Split split, Limits limits, ByteBpeTokenizer tokenizer) throws IOException {
        validateSplit(manifest, split);
        TokenStore train = encodePartition(manifest, split.train(), limits, tokenizer);
        TokenStore validation = encodePartition(manifest, split.validation(), limits, tokenizer);
        TokenStore test = encodePartition(manifest, split.test(), limits, tokenizer);
        var hash = hash(); update(hash, manifest.digest());
        update(hash, Double.toHexString(split.trainFraction()) + ":" + Double.toHexString(split.validationFraction()));
        for (var partition : List.of(split.train(), split.validation(), split.test())) {
            update(hash, "partition\n"); for (Entry file : partition) update(hash, file.path() + "\n");
        }
        for (TokenStore store : List.of(train, validation, test)) {
            update(hash, Long.toString(store.size()) + "\n");
            for (long i = 0; i < store.size(); i++) {
                int id = store.get(i); hash.update((byte) (id >>> 24)); hash.update((byte) (id >>> 16));
                hash.update((byte) (id >>> 8)); hash.update((byte) id);
            }
        }
        return new Encoded(train, validation, test, byteCount(split.train()), byteCount(split.validation()),
                byteCount(split.test()), HexFormat.of().formatHex(hash.digest()));
    }

    private static TokenStore encodePartition(Manifest manifest, List<Entry> files, Limits limits, ByteBpeTokenizer tokenizer) throws IOException {
        var builder = new TokenStore.Chunked.Builder(limits.maxTokensPerPartition());
        for (Entry file : files) verifiedRead(manifest, file, limits, line -> builder.append(tokenizer.encode(line)));
        return builder.build();
    }
    private static long byteCount(List<Entry> files) { return files.stream().mapToLong(Entry::bytes).sum(); }
    private static void validateSplit(Manifest manifest, Split partition) {
        if (!split(manifest, partition.trainFraction(), partition.validationFraction()).equals(partition))
            throw new IllegalArgumentException("Split must match canonical disjoint manifest boundaries");
    }
    private static void verifiedRead(Manifest manifest, Entry entry, Limits limits, Consumer<String> consumer) throws IOException {
        Path path = manifest.root().resolve(entry.path()).normalize();
        if (!path.startsWith(manifest.root()) || Files.isSymbolicLink(path)) throw new IOException("Unsafe corpus path");
        var stats = read(path, limits, limits.maxFileBytes(), consumer);
        if (!entry.digest().equals(stats.digest()) || entry.bytes() != stats.bytes() || stats.rejection() != null)
            throw new IOException("Corpus file changed since manifest: " + entry.path());
    }

    private record Stats(long bytes, long points, String digest, String rejection) {}
    private static Stats read(Path file, Limits limits, int remainingBytes, Consumer<String> consumer) throws IOException {
        var hash = hash();
        long[] stats = new long[4]; // code points, alphabetic ASCII, whitespace, disallowed controls
        boolean[] longLine = {false};
        try (var bounded = new BoundedInput(Files.newInputStream(file), Math.min(limits.maxFileBytes(), remainingBytes));
             var reader = new BufferedReader(new InputStreamReader(new DigestInputStream(bounded, hash),
                     StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)), 4096)) {
            var line = new StringBuilder(); int column = 0;
            for (int ch; (ch = reader.read()) != -1;) {
                if (++column > limits.maxLineCharacters()) longLine[0] = true;
                // Keep reading/digesting pathological lines with bounded buffers; they are never emitted.
                if (column <= limits.maxLineCharacters()) line.append((char) ch);
                if (ch == '\n') {
                    inspect(line.toString(), stats); if (!longLine[0]) consumer.accept(line.toString());
                    line.setLength(0); column = 0;
                }
            }
            if (!line.isEmpty()) { inspect(line.toString(), stats); if (!longLine[0]) consumer.accept(line.toString()); }
            String reason = bounded.count == 0 ? "empty" : longLine[0] ? "pathological line length"
                    : stats[3] > 0 ? "disallowed control characters"
                    : stats[0] == 0 || stats[1] / (double) stats[0] < .35 ? "too little English alphabetic prose"
                    : stats[2] / (double) stats[0] < .04 || stats[2] / (double) stats[0] > .6 ? "pathological whitespace ratio" : null;
            return new Stats(bounded.count, stats[0], HexFormat.of().formatHex(hash.digest()), reason);
        }
    }
    private static void inspect(String text, long[] stats) {
        text.codePoints().forEach(cp -> {
            stats[0]++;
            if (cp >= 'a' && cp <= 'z' || cp >= 'A' && cp <= 'Z') stats[1]++;
            if (Character.isWhitespace(cp)) stats[2]++;
            if (Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t') stats[3]++;
        });
    }
    private static final class BoundedInput extends FilterInputStream {
        private final long maximum; private long count;
        BoundedInput(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        public int read() throws IOException {
            int value = in.read(); if (value != -1 && ++count > maximum) throw new IllegalArgumentException("Streaming corpus byte limit exceeded"); return value;
        }
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, (int) Math.min(length, maximum - count + 1));
            if (read > 0 && (count += read) > maximum) throw new IllegalArgumentException("Streaming corpus byte limit exceeded"); return read;
        }
    }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace(File.separatorChar, '/'); }
    private static MessageDigest hash() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void update(MessageDigest hash, String text) { hash.update(text.getBytes(StandardCharsets.UTF_8)); }
}
