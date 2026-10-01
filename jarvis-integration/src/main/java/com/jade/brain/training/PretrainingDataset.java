package com.jade.brain.training;

import com.jade.brain.tokenizer.ByteBpeTokenizer;
import com.jade.brain.tokenizer.TokenizerArtifact;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded local UTF-8 ingestion and document-delimited, mmap-friendly token partitions. */
public final class PretrainingDataset {
    private PretrainingDataset() {}
    public static final int MAX_DEPTH = 8, MAX_FILES = 10_000, MAX_FILE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_LINE_CHARS = 16_384;
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024 * 1024;
    private static final byte[] MAGIC = "JDTOK001".getBytes(StandardCharsets.US_ASCII);
    public record Entry(String path, String partition, long bytes, long codePoints, String sha256, boolean accepted, String reason) {}
    public record Result(List<Entry> entries, String datasetSha256, long[] tokens, long[] documents,
                         long duplicateDocuments, long crossPartitionLines, long crossPartitionParagraphs) {}

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String sha(byte[] data) { return HexFormat.of().formatHex(digest().digest(data)); }
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static byte[] boundedRead(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("File grew beyond size limit: " + path);
            return bytes;
        }
    }
    private static String reason(String text) {
        long points = text.codePoints().count(), letters = text.codePoints().filter(c -> c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z').count();
        long spaces = text.codePoints().filter(Character::isWhitespace).count();
        if (points == 0) return "empty";
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            return "disallowed control character";
        for (String line : text.split("\n", -1)) if (line.length() > MAX_LINE_CHARS) return "line length exceeded";
        if (letters / (double) points < .35) return "too little English alphabetic prose";
        if (spaces / (double) points < .04 || spaces / (double) points > .6) return "pathological whitespace ratio";
        return "";
    }
    private static List<String> lines(String text) {
        var result = new ArrayList<String>(); int start = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') {
            result.add(text.substring(start, i + 1)); start = i + 1;
        }
        if (start < text.length()) result.add(text.substring(start));
        return result;
    }
    private static void write(FileChannel out, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) out.write(bytes);
    }
    private static ByteBuffer number(long value) { return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).flip(); }
    private static void putAt(FileChannel out, long offset, ByteBuffer bytes) throws IOException {
        out.position(offset); write(out, bytes);
    }
    private static void writePartition(Path target, int partition, List<Entry> entries, Path root,
                                       ByteBpeTokenizer tokenizer, String tokenizerDigest, String datasetDigest,
                                       long[] tokens, long[] documents) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            write(out, ByteBuffer.allocate(128));
            for (Entry entry : entries) if (entry.partition().equals(new String[] {"train", "validation", "test"}[partition])) {
                byte[] source = boundedRead(root.resolve(entry.path()));
                if (source.length != entry.bytes() || !sha(source).equals(entry.sha256()))
                    throw new IOException("Dataset document changed after scan: " + entry.path());
                String text = utf8(source);
                long countOffset = out.position(); write(out, number(0)); long count = 0;
                for (String line : lines(text)) {
                    int[] ids = tokenizer.encode(line);
                    ByteBuffer chunk = ByteBuffer.allocate(Math.multiplyExact(ids.length, 4)).order(ByteOrder.LITTLE_ENDIAN);
                    for (int id : ids) chunk.putInt(id);
                    chunk.flip(); write(out, chunk); count = Math.addExact(count, ids.length);
                }
                long end = out.position(); putAt(out, countOffset, number(count)); out.position(end);
                tokens[partition] = Math.addExact(tokens[partition], count); documents[partition]++;
            }
            MessageDigest payload = digest(); ByteBuffer block = ByteBuffer.allocate(65536);
            out.position(128);
            while (out.read(block) > 0) { block.flip(); payload.update(block); block.clear(); }
            ByteBuffer header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
            header.put(MAGIC).putInt(1).putInt(partition).put(hex(tokenizerDigest)).put(hex(datasetDigest))
                    .putLong(documents[partition]).putLong(tokens[partition]).put(payload.digest());
            header.flip(); putAt(out, 0, header); out.force(true);
        } catch (RuntimeException | IOException failure) { Files.deleteIfExists(temp); throw failure; }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void overlap(Map<String,Integer> seen, String value, int partition, long[] cross) {
        if (value.isBlank()) return;
        String key = sha(value.strip().getBytes(StandardCharsets.UTF_8));
        int bit = 1 << partition, old = seen.getOrDefault(key, 0);
        if (old != 0 && (old & bit) == 0) cross[0]++;
        if (seen.size() >= 2_000_000 && old == 0) throw new IllegalArgumentException("Lexical audit entry limit exceeded");
        seen.put(key, old | bit);
    }
    public static Result prepare(Path source, Path artifact, Path output) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Dataset root missing");
        var loaded = TokenizerArtifact.load(artifact);
        var tokenizer = new ByteBpeTokenizer(loaded.model());
        if (tokenizer.vocabularySize() != 1024) throw new IOException("Expected 1024-token BPE");
        List<Path> paths;
        try (var walk = Files.walk(root, MAX_DEPTH + 1)) { paths = walk.limit(MAX_FILES * 8L + 1).toList(); }
        if (paths.size() > MAX_FILES * 8L) throw new IOException("Dataset directory entry limit exceeded");
        for (Path path : paths) if (root.relativize(path).getNameCount() > MAX_DEPTH)
            throw new IOException("Dataset directory depth limit exceeded at " + root.relativize(path));
        paths = paths.stream().filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(p -> root.relativize(p).toString())).toList();
        if (paths.size() > MAX_FILES) throw new IOException("Dataset file limit exceeded");
        var entries = new ArrayList<Entry>(); var duplicate = new HashSet<String>(); long total = 0, duplicateDocuments = 0;
        for (Path path : paths) {
            String name = root.relativize(path).toString().replace(File.separatorChar, '/');
            String why = ""; long size = 0, points = 0; String sha256 = "";
            if (name.contains("\t") || name.contains("\n") || !name.endsWith(".txt") ||
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) why = "unsupported name, type or symbolic link";
            else if (!Files.isReadable(path)) why = "unreadable";
            else {
                size = Files.size(path);
                if (size > MAX_FILE_BYTES) why = "file size exceeded";
                else if (size > MAX_TOTAL_BYTES - total) why = "total corpus byte limit exceeded";
                else {
                    total += size;
                    byte[] bytes = boundedRead(path); sha256 = sha(bytes);
                    try {
                        String text = utf8(bytes); points = text.codePoints().count(); why = reason(text);
                        if (why.isEmpty() && !duplicate.add(sha256)) { why = "duplicate document"; duplicateDocuments++; }
                    } catch (CharacterCodingException invalid) { why = "invalid UTF-8"; }
                }
            }
            entries.add(new Entry(name, "-", size, points, sha256, why.isEmpty(), why));
        }
        var accepted = entries.stream().filter(Entry::accepted).toList();
        if (accepted.size() < 3) throw new IOException("Need at least three accepted whole documents");
        int train = Math.min(accepted.size() - 2, Math.max(1, (int) Math.floor(accepted.size() * .98)));
        int validation = Math.max(1, (int) Math.floor(accepted.size() * .01));
        validation = Math.min(validation, accepted.size() - train - 1);
        var partitions = new HashMap<String,String>();
        for (int i = 0; i < accepted.size(); i++) partitions.put(accepted.get(i).path(),
                i < train ? "train" : i < train + validation ? "validation" : "test");
        entries.replaceAll(e -> e.accepted() ? new Entry(e.path(), partitions.get(e.path()), e.bytes(),
                e.codePoints(), e.sha256(), true, "") : e);
        var identity = digest();
        for (Entry e : entries) if (e.accepted()) identity.update((e.path() + "\0" + e.partition() + "\0" +
                e.bytes() + "\0" + e.sha256() + "\n").getBytes(StandardCharsets.UTF_8));
        String datasetDigest = HexFormat.of().formatHex(identity.digest());
        Files.createDirectories(output);
        var manifest = new StringBuilder("path\tpartition\tbytes\tcode_points\tsha256\taccepted\treason\n");
        for (Entry e : entries) manifest.append(e.path()).append('\t').append(e.partition()).append('\t')
                .append(e.bytes()).append('\t').append(e.codePoints()).append('\t').append(e.sha256()).append('\t')
                .append(e.accepted()).append('\t').append(e.reason()).append('\n');
        Files.writeString(output.resolve("manifest.tsv"), manifest, StandardCharsets.UTF_8);
        var lineSeen = new HashMap<String,Integer>(); var paragraphSeen = new HashMap<String,Integer>();
        long[] crossLine = {0}, crossParagraph = {0};
        for (Entry e : entries) if (e.accepted()) {
            int partition = List.of("train", "validation", "test").indexOf(e.partition());
            String text = utf8(boundedRead(root.resolve(e.path())));
            for (String line : lines(text)) overlap(lineSeen, line, partition, crossLine);
            for (String paragraph : text.split("(?:\r?\n){2,}")) overlap(paragraphSeen, paragraph, partition, crossParagraph);
        }
        long[] tokens = new long[3], docs = new long[3];
        for (int p = 0; p < 3; p++) writePartition(output.resolve(new String[] {"train", "validation", "test"}[p] + ".jtok"),
                p, entries, root, tokenizer, loaded.digest(), datasetDigest, tokens, docs);
        var result = new Result(List.copyOf(entries), datasetDigest, tokens, docs,
                duplicateDocuments, crossLine[0], crossParagraph[0]);
        Files.writeString(output.resolve("identity.txt"), "dataset=" + datasetDigest + "\ntokenizer=" + loaded.digest() +
                "\ntrain_tokens=" + tokens[0] + "\nvalidation_tokens=" + tokens[1] + "\ntest_tokens=" + tokens[2] +
                "\nduplicate_documents=" + duplicateDocuments + "\ncross_partition_lines=" + crossLine[0] +
                "\ncross_partition_paragraphs=" + crossParagraph[0] + "\n", StandardCharsets.US_ASCII);
        return result;
    }
    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("source-directory tokenizer-artifact output-directory");
        Result r = prepare(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println("DATASET documents=" + r.documents()[0] + "/" + r.documents()[1] + "/" + r.documents()[2] +
                " tokens=" + r.tokens()[0] + "/" + r.tokens()[1] + "/" + r.tokens()[2] +
                " rejected=" + r.entries().stream().filter(e -> !e.accepted()).count() + " sha256=" + r.datasetSha256() +
                " duplicateDocs=" + r.duplicateDocuments() + " crossLines=" + r.crossPartitionLines() +
                " crossParagraphs=" + r.crossPartitionParagraphs());
    }
}
