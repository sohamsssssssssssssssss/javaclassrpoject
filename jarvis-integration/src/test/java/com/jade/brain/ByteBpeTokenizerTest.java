package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.generation.JadeTextGenerator;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ByteBpeTokenizerTest {
    private static final List<String> CORPUS = List.of("hello", "hello jade", "jade", "jade brain", "brain",
            "language", "language model", "transformer", "attention", "token", "tokenizer", "generation", "recursion");
    private static final BpeTokenizerModel TRAINED = new BpeTrainer().train(CORPUS, 300);
    private final ByteBpeTokenizer tokenizer = new ByteBpeTokenizer(TRAINED);

    private void roundTrip(String... samples) {
        for (String sample : samples) assertEquals(sample, tokenizer.decode(tokenizer.encode(sample)));
    }

    private static BpeVocabulary vocabulary(byte[]... merged) {
        var tokens = new ArrayList<>(BpeVocabulary.base().tokens());
        for (byte[] bytes : merged) tokens.add(new BpeToken(tokens.size(), bytes));
        return new BpeVocabulary(tokens);
    }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    @Test void baseVocabularyContainsAll256DirectByteIds() {
        var vocabulary = BpeVocabulary.base();
        assertEquals(256, vocabulary.size());
        for (int id = 0; id < 256; id++) {
            assertEquals(id, vocabulary.token(id).id());
            assertArrayEquals(new byte[] {(byte) id}, vocabulary.token(id).bytes());
            assertEquals(id, vocabulary.tokenId(new byte[] {(byte) id}).orElseThrow());
        }
        assertTrue(vocabulary.tokenId(ascii("not a base token")).isEmpty());
    }
    @Test void baseEncodingMatchesRawUtf8UnsignedBytes() {
        var base = new ByteBpeTokenizer(new BpeTokenizerModel(BpeVocabulary.base(), List.of()));
        String text = "A\u0000é🤖";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int[] ids = base.encode(text);
        assertEquals(bytes.length, ids.length);
        for (int i = 0; i < ids.length; i++) assertEquals(Byte.toUnsignedInt(bytes[i]), ids[i]);
        assertEquals(text, base.decode(ids));
    }
    @Test void asciiRoundTrip() { roundTrip("hello jade", "numbers 1234567890"); }
    @Test void punctuationRoundTrip() { roundTrip("Hello, world!", "don't break punctuation.", "symbols !@#$%^&*()"); }
    @Test void whitespaceExactRoundTrip() {
        roundTrip("hello jade", "hello  jade", " hello jade", "hello jade ", "hello\tjade", "hello\njade",
                "spaces    stay    exact", "tabs\tstay", " \t\r\n ");
        assertFalse(Arrays.equals(tokenizer.encode("hello jade"), tokenizer.encode("hello  jade")));
    }
    @Test void newlineRoundTrip() { roundTrip("newlines\nstay", "\r\n\n\r"); }
    @Test void latinUnicodeRoundTrip() { roundTrip("café", "naïve", "cafe\u0301"); }
    @Test void cjkRoundTrip() { roundTrip("こんにちは", "你好"); }
    @Test void cyrillicRoundTrip() { roundTrip("Привет"); }
    @Test void arabicRoundTrip() { roundTrip("مرحبا"); }
    @Test void devanagariRoundTrip() { roundTrip("नमस्ते"); }
    @Test void emojiRoundTrip() { roundTrip("🤖", "🔥", "😭", "🚀", "��", "👨‍👩‍👧‍👦"); }
    @Test void multilingualMixedRoundTrip() { roundTrip("JADE 🤖 says: नमस्ते, world! 🚀"); }
    @Test void emptyInputRoundTrip() {
        assertArrayEquals(new int[0], tokenizer.encode(""));
        assertEquals("", tokenizer.decode(new int[0]));
    }
    @Test void isolatedSurrogatesRejectWithoutReplacement() {
        for (String text : List.of("\uD800", "\uDC00", "x\uD800y", "\uDC00\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> tokenizer.encode(text));
            assertThrows(IllegalArgumentException.class, () -> new BpeTrainer().train(List.of(text), 257));
        }
        roundTrip("\uD800\uDC00", "\uDBFF\uDFFF");
    }
    @Test void malformedDecodedUtf8RejectsButSplitValidBytesDecodeTogether() {
        assertThrows(IllegalArgumentException.class, () -> tokenizer.decode(new int[] {255}));
        assertThrows(IllegalArgumentException.class, () -> tokenizer.decode(new int[] {192, 128}));
        assertEquals("é", tokenizer.decode(new int[] {195, 169}));
    }
    @Test void deterministicTrainingIgnoresCorpusOrdering() {
        var again = new BpeTrainer().train(CORPUS, 300);
        var reversed = new ArrayList<>(CORPUS);
        Collections.reverse(reversed);
        var reordered = new BpeTrainer().train(reversed, 300);
        assertEquals(TRAINED.merges(), again.merges());
        assertEquals(TRAINED.merges(), reordered.merges());
        assertEquals(TRAINED.vocabulary().tokens(), again.vocabulary().tokens());
        assertEquals(TRAINED.vocabulary().tokens(), reordered.vocabulary().tokens());
    }
    @Test void frequencyTiesUseLowestLeftThenRightId() {
        var first = new BpeTrainer().train(List.of("ac", "ab", "ba"), 257);
        var second = new BpeTrainer().train(List.of("ba", "ab", "ac"), 257);
        assertEquals(List.of(new BpeMerge(97, 98, 256, 0)), first.merges());
        assertEquals(first.merges(), second.merges());
    }
    @Test void mergesNeverCrossCorpusBoundaries() {
        var singletons = new BpeTrainer().train(List.of("a", "b"), 300);
        assertEquals(256, singletons.vocabularySize());
        assertEquals(0, singletons.mergeCount());
        var separate = new BpeTrainer().train(List.of("ab", "cd"), 258);
        assertEquals(List.of(new BpeMerge(97, 98, 256, 0), new BpeMerge(99, 100, 257, 1)), separate.merges());
    }
    @Test void mergeResultBytesMustEqualConcatenation() {
        assertThrows(IllegalArgumentException.class, () -> new BpeTokenizerModel(vocabulary(ascii("ac")),
                List.of(new BpeMerge(97, 98, 256, 0))));
        for (BpeMerge merge : TRAINED.merges()) {
            byte[] left = TRAINED.vocabulary().token(merge.leftTokenId()).bytes();
            byte[] right = TRAINED.vocabulary().token(merge.rightTokenId()).bytes();
            byte[] joined = Arrays.copyOf(left, left.length + right.length);
            System.arraycopy(right, 0, joined, left.length, right.length);
            assertArrayEquals(joined, TRAINED.vocabulary().token(merge.resultTokenId()).bytes());
        }
    }
    @Test void duplicateMergePairsReject() {
        assertThrows(IllegalArgumentException.class, () -> new BpeTokenizerModel(vocabulary(ascii("ab")),
                List.of(new BpeMerge(97, 98, 256, 0), new BpeMerge(97, 98, 256, 1))));
    }
    @Test void duplicateMergeRanksReject() {
        assertThrows(IllegalArgumentException.class, () -> new BpeTokenizerModel(vocabulary(ascii("ab"), ascii("cd")),
                List.of(new BpeMerge(97, 98, 256, 0), new BpeMerge(99, 100, 257, 0))));
    }
    @Test void invalidIdsReject() {
        assertThrows(IllegalArgumentException.class, () -> tokenizer.decode(new int[] {-1}));
        assertThrows(IllegalArgumentException.class, () -> tokenizer.decode(new int[] {tokenizer.vocabularySize()}));
        assertThrows(IllegalArgumentException.class, () -> new BpeToken(-1, ascii("a")));
        assertThrows(IllegalArgumentException.class, () -> new BpeMerge(0, 0, 256, -1));
        assertThrows(IllegalArgumentException.class, () -> new BpeTokenizerModel(BpeVocabulary.base(),
                List.of(new BpeMerge(97, 98, 256, 0))));
    }
    @Test void overlappingPairsReplaceLeftToRightWithoutOverlap() {
        var trained = new BpeTrainer().train(List.of("aaaa"), 257);
        var local = new ByteBpeTokenizer(trained);
        assertEquals(List.of(new BpeMerge(97, 97, 256, 0)), trained.merges());
        assertArrayEquals(new int[] {256, 97}, local.encode("aaa"));
        assertArrayEquals(new int[] {256, 256, 256}, local.encode("aaaaaa"));
        assertEquals("aaa", local.decode(new int[] {256, 97}));
    }
    @Test void encodingUsesRanksInsteadOfLongestVocabularyMatch() {
        var vocabulary = vocabulary(ascii("ab"), ascii("bc"), ascii("abc"));
        var rules = List.of(new BpeMerge(97, 98, 256, 1), new BpeMerge(98, 99, 257, 0),
                new BpeMerge(256, 99, 258, 2));
        var model = new BpeTokenizerModel(vocabulary, rules);
        assertEquals(0, model.merges().getFirst().rank());
        var local = new ByteBpeTokenizer(model);
        assertArrayEquals(new int[] {97, 257}, local.encode("abc"));
        assertEquals("abc", local.decode(local.encode("abc")));
    }
    @Test void learnedMergesStrictlyReduceKnownPatternTokenCount() {
        int bytes = "hello".getBytes(StandardCharsets.UTF_8).length;
        assertTrue(tokenizer.encode("hello").length < bytes);
    }
    @Test void encodingAndDecodingDoNotMutateVocabularyOrInputIds() {
        List<BpeToken> before = List.copyOf(TRAINED.vocabulary().tokens());
        List<BpeMerge> merges = List.copyOf(TRAINED.merges());
        int[] ids = tokenizer.encode("hello jade");
        int[] copy = ids.clone();
        for (int i = 0; i < 10; i++) assertEquals("hello jade", tokenizer.decode(tokenizer.encode("hello jade")));
        tokenizer.decode(ids);
        assertArrayEquals(copy, ids);
        assertEquals(before, TRAINED.vocabulary().tokens());
        assertEquals(merges, TRAINED.merges());
    }
    @Test void tokenizerStatisticsMatchModel() {
        assertEquals(256, TRAINED.baseVocabularySize());
        assertEquals(300, tokenizer.vocabularySize());
        assertEquals(TRAINED.vocabularySize(), tokenizer.vocabularySize());
        assertEquals(TRAINED.merges().size(), TRAINED.mergeCount());
    }
    @Test void tokenizerModelCompatibilityAndRandomForwardPass() {
        BrainTokenizer interfaceTokenizer = tokenizer;
        var config = new BrainConfig(interfaceTokenizer.vocabularySize(), 32, 32, 4, 1, 64);
        var model = new JadeLanguageModel(config, 26167);
        int[] ids = tokenizer.encode("hello jade");
        double[][] logits = model.forward(ids);
        assertEquals(ids.length, logits.length);
        assertEquals(tokenizer.vocabularySize(), logits[0].length);
        for (double[] row : logits) for (double value : row) assertTrue(Double.isFinite(value));
        var generator = new JadeTextGenerator(model, tokenizer, ignored -> 104);
        assertEquals("h", generator.generate(ids, 1).generatedText());
        var wrong = new JadeLanguageModel(new BrainConfig(8, 32, 32, 4, 1, 64), 26167);
        assertThrows(IllegalArgumentException.class, () -> new JadeTextGenerator(wrong, tokenizer, ignored -> 0));
    }
    @Test void tokensHaveDefensiveCopiesAndDeterministicEquality() {
        byte[] bytes = ascii("ab");
        var token = new BpeToken(256, bytes);
        bytes[0] = 0;
        byte[] returned = token.bytes();
        returned[0] = 0;
        var equal = new BpeToken(256, ascii("ab"));
        assertEquals(equal, token);
        assertEquals(equal.hashCode(), token.hashCode());
        assertNotEquals(new BpeToken(257, ascii("ab")), token);
        assertArrayEquals(ascii("ab"), token.bytes());
        assertEquals("256:0x6162", token.toString());
        assertThrows(IllegalArgumentException.class, () -> new BpeToken(0, new byte[0]));
    }
    @Test void vocabulariesAndModelsAreImmutableAndRejectDuplicatesOrMissingBase() {
        var entries = new ArrayList<>(BpeVocabulary.base().tokens());
        var copy = new BpeVocabulary(entries);
        entries.clear();
        assertEquals(256, copy.size());
        assertThrows(UnsupportedOperationException.class, () -> copy.tokens().clear());
        assertThrows(UnsupportedOperationException.class, () -> TRAINED.merges().clear());
        assertThrows(IllegalArgumentException.class, () -> vocabulary(ascii("a")));
        assertThrows(IllegalArgumentException.class, () -> new BpeVocabulary(List.of()));
        var invalid = new ArrayList<>(copy.tokens());
        invalid.set(0, new BpeToken(0, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new BpeVocabulary(invalid));
    }
    @Test void orphanTokensAndWrongRankDependenciesReject() {
        var vocabulary = vocabulary(ascii("ab"), ascii("abc"));
        assertThrows(IllegalArgumentException.class, () -> new BpeTokenizerModel(vocabulary, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BpeTokenizerModel(vocabulary,
                List.of(new BpeMerge(256, 99, 257, 0), new BpeMerge(97, 98, 256, 1))));
    }
    @Test void resourceBoundsAndExhaustedCorporaAreExplicit() {
        var trainer = new BpeTrainer();
        assertThrows(IllegalArgumentException.class, () -> trainer.train(CORPUS, 255));
        assertThrows(IllegalArgumentException.class, () -> trainer.train(CORPUS, 4097));
        assertThrows(IllegalArgumentException.class, () -> trainer.train(List.of("a".repeat(65_537)), 257));
        assertThrows(IllegalArgumentException.class, () -> trainer.train(Collections.nCopies(4097, ""), 257));
        assertThrows(IllegalArgumentException.class, () -> new BpeToken(256, new byte[4097]));
        assertThrows(IllegalArgumentException.class, () -> tokenizer.encode("a".repeat(ByteBpeTokenizer.MAX_INPUT_BYTES + 1)));
        assertEquals(256, trainer.train(List.of("", ""), 300).vocabularySize());
        assertEquals(256, trainer.train(List.of(), 300).vocabularySize());
    }
    @Test void deterministicRandomValidUnicodeRoundTrips() {
        var random = new Random(26167);
        for (int trial = 0; trial < 100; trial++) {
            var text = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                int point;
                do point = random.nextInt(0x110000); while (point >= 0xD800 && point <= 0xDFFF);
                text.appendCodePoint(point);
            }
            roundTrip(text.toString());
        }
    }
    @Test void deterministicTokenizerDemo() {
        String sample = "JADE 🤖 says: hello jade";
        int[] ids = tokenizer.encode(sample);
        String decoded = tokenizer.decode(ids);
        assertEquals(sample, decoded);
        System.out.println("JADE BYTE-BPE TOKENIZER DEMO\nBase vocabulary: 256\nTarget vocabulary: 300"
                + "\nFinal vocabulary: " + tokenizer.vocabularySize() + "\nLearned merges: " + TRAINED.mergeCount());
        for (BpeMerge merge : TRAINED.merges().subList(0, Math.min(10, TRAINED.mergeCount()))) {
            System.out.println("rank " + merge.rank() + ": " + merge.leftTokenId() + " + " + merge.rightTokenId()
                    + " -> " + merge.resultTokenId() + " [0x"
                    + HexFormat.of().formatHex(TRAINED.vocabulary().token(merge.resultTokenId()).bytes()) + "]");
        }
        System.out.println("Sample: " + sample + "\nUTF-8 bytes: " + sample.getBytes(StandardCharsets.UTF_8).length
                + "\nBPE token IDs: " + Arrays.toString(ids) + "\nBPE token count: " + ids.length
                + "\nDecoded: " + decoded + "\nExact round trip: YES\nUnknown token required: NO"
                + "\nExternal tokenizer: NO\nDownloaded vocabulary: NO\nDownloaded merges: NO"
                + "\nCompression hello: " + tokenizer.encode("hello").length + " tokens / 5 bytes");
    }
}
