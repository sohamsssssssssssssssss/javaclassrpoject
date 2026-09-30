package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.generation.JadeTextGenerator;
import com.jade.brain.generation.TokenSelector;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.objective.LanguageModelExample;
import com.jade.brain.tokenizer.BpeTokenizerModel;
import com.jade.brain.tokenizer.BpeVocabulary;
import com.jade.brain.tokenizer.ByteBpeTokenizer;
import com.jade.brain.training.SgdTrainer;
import com.jade.brain.training.Sgd;
import com.jade.brain.training.TextCorpus;
import com.jade.brain.training.TokenDataset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CorpusTrainingTest {
    @TempDir Path directory;
    private static final ByteBpeTokenizer TOKENIZER = new ByteBpeTokenizer(
            new BpeTokenizerModel(BpeVocabulary.base(), List.of()));

    @Test void corpusIsStrictBoundedOrderedAndUnicodeExact() throws IOException {
        Path b = directory.resolve("b.txt"), a = directory.resolve("a.txt");
        Files.writeString(a, "café 🤖\n", StandardCharsets.UTF_8);
        Files.writeString(b, "jade\n", StandardCharsets.UTF_8);
        String joined = "café 🤖\njade\n";
        var corpus = TextCorpus.load(List.of(b, a), TOKENIZER, 100, 100);
        assertArrayEquals(TOKENIZER.encode(joined), corpus.tokenIds());
        assertEquals(2, corpus.fileCount());
        assertEquals(joined.getBytes(StandardCharsets.UTF_8).length, corpus.utf8Bytes());
        assertEquals(joined.codePointCount(0, joined.length()), corpus.codePoints());
        assertEquals(corpus.tokenIds().length, corpus.tokenCount());
        assertThrows(IllegalArgumentException.class,
                () -> TextCorpus.load(List.of(a, a), TOKENIZER, 100, 100));
        assertThrows(IOException.class,
                () -> TextCorpus.load(List.of(directory.resolve("missing.txt")), TOKENIZER, 100, 100));
        assertThrows(IOException.class,
                () -> TextCorpus.load(List.of(directory), TOKENIZER, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> TextCorpus.load(List.of(a, b), TOKENIZER, 3, 100));
        assertThrows(IllegalArgumentException.class,
                () -> TextCorpus.load(List.of(a, b), TOKENIZER, 100, 3));
        Files.writeString(a, ""); Files.writeString(b, "");
        assertThrows(IllegalArgumentException.class,
                () -> TextCorpus.load(List.of(a, b), TOKENIZER, 100, 100));
        Files.write(a, new byte[]{(byte) 0xC3, (byte) 0x28});
        assertThrows(IOException.class,
                () -> TextCorpus.load(List.of(a), TOKENIZER, 100, 100));
    }

    @Test void sequentialSplitPreventsLeakageAndRetainsIncompleteBatch() {
        int[] ids = new int[16]; for (int i = 0; i < ids.length; i++) ids[i] = i;
        var set = TokenDataset.split(ids, 3, 2, .75);
        assertEquals(12, set.splitIndex());
        assertEquals(12, set.trainTokens().length); assertEquals(4, set.validationTokens().length);
        assertEquals(5, set.train().size()); assertEquals(1, set.validation().size());
        for (var window : set.train()) {
            for (int id : window.example().inputTokenIds()) assertTrue(id < set.splitIndex());
            for (int id : window.example().targetTokenIds()) assertTrue(id < set.splitIndex());
        }
        for (var window : set.validation()) {
            for (int id : window.example().inputTokenIds()) assertTrue(id >= set.splitIndex());
            for (int id : window.example().targetTokenIds()) assertTrue(id >= set.splitIndex());
        }
        var batches = TokenDataset.batches(set.train(), 2);
        assertEquals(List.of(2, 2, 1), batches.stream().map(b -> b.windows().size()).toList());
        assertEquals(set.train(), batches.stream().flatMap(b -> b.windows().stream()).toList());
        assertThrows(IllegalArgumentException.class, () -> TokenDataset.split(ids, 3, 0, .75));
        assertThrows(IllegalArgumentException.class, () -> TokenDataset.split(ids, 3, 1, .99));
        assertThrows(IllegalArgumentException.class, () -> TokenDataset.batches(set.train(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> TokenDataset.split(new int[100_000], 2, 1, .75));
        assertArrayEquals(new int[]{0, 1, 2}, batches.getFirst().inputs()[0]);
        assertArrayEquals(new int[]{1, 2, 3}, batches.getFirst().targets()[0]);
        int[][] detached = batches.getFirst().inputs(); detached[0][0] = 999;
        assertEquals(0, batches.getFirst().inputs()[0][0]);
        var hugeStride = TokenDataset.split(ids, 3, Integer.MAX_VALUE, .75);
        assertEquals(1, hugeStride.train().size());
    }

    @Test void corpusUsesExistingBpeMergesAndRejectsUnreadableFiles() throws IOException {
        Path file = directory.resolve("merged.txt");
        String text = "jade learns words. jade learns words.\n";
        Files.writeString(file, text, StandardCharsets.UTF_8);
        var tokenizer = new ByteBpeTokenizer(new com.jade.brain.tokenizer.BpeTrainer()
                .train(List.of(text), 264));
        var corpus = TextCorpus.load(List.of(file), tokenizer, 100, 100);
        assertTrue(corpus.tokenCount() < corpus.utf8Bytes());
        assertEquals(text, tokenizer.decode(corpus.tokenIds()));
        int[] detached = corpus.tokenIds(); detached[0] = -1;
        assertArrayEquals(tokenizer.encode(text), corpus.tokenIds());
        var permissions = Files.getPosixFilePermissions(file);
        try {
            Files.setPosixFilePermissions(file, java.util.Set.of());
            assertFalse(Files.isReadable(file), "Unreadable-file test requires an unprivileged process");
            assertThrows(IOException.class, () -> TextCorpus.load(List.of(file), tokenizer, 100, 100));
        } finally {
            Files.setPosixFilePermissions(file, permissions);
        }
    }

    @Test void configurationAndRejectedStepsLeaveNoPartialUpdate() throws IOException {
        for (int[] dims : new int[][]{{0, 1, 1, 1}, {4097, 1, 1, 1}, {4, 0, 1, 1},
                {4, 1, 0, 1}, {4, 1, 1025, 1}, {4, 1, 1, 0}, {4, 1, 1, 1001}})
            assertThrows(IllegalArgumentException.class, () -> new SgdTrainer.Config(
                    dims[0], dims[1], dims[2], dims[3], .02, .9, 4096, 4096));
        for (double rate : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,
                    () -> new SgdTrainer.Config(4, 1, 1, 1, rate, .9, 4096, 4096));
        for (double fraction : new double[]{0, 1, -.1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,
                    () -> new SgdTrainer.Config(4, 1, 1, 1, .02, fraction, 4096, 4096));
        assertThrows(IllegalArgumentException.class,
                () -> new SgdTrainer.Config(4, 1, 1, 1, .02, .9, 0, 4096));
        assertThrows(IllegalArgumentException.class,
                () -> new SgdTrainer.Config(4, 1, 1, 1, .02, .9, 4096, 1));
        var model = new JadeLanguageModel(new BrainConfig(256, 4, 4, 2, 1, 8), 26167);
        var before = snapshot(model);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SgdTrainer.BatchGradient(invalid, 1, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> new Matrix(new double[][]{{invalid}}));
            assertThrows(IllegalArgumentException.class, () -> SgdTrainer.perplexity(invalid));
        }
        var gradient = new java.util.LinkedHashMap<>(model.backwardTokens(TOKENIZER.encode("jade")).gradients());
        double[][] last = gradient.get("lmHead.weight").toArray(); last[0][0] = Double.MAX_VALUE;
        gradient.put("lmHead.weight", new Matrix(last));
        assertThrows(IllegalArgumentException.class, () -> Sgd.step(model, gradient, 2));
        Path file = directory.resolve("unsafe.txt");
        Files.writeString(file, "jade learns words.\n".repeat(4), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> SgdTrainer.runFiles(model, TOKENIZER,
                List.of(file), new SgdTrainer.Config(4, 4, 4, 1, Double.MAX_VALUE, .9, 4096, 4096)));
        assertBitsEqual(before, model);
    }

    @Test void batchLossAndGradientEqualIndependentExamples() {
        var model = new JadeLanguageModel(new BrainConfig(8, 4, 2, 1, 1, 3), 26167);
        var before = snapshot(model);
        var first = new TokenDataset.Window(0, LanguageModelExample.fromTokens(new int[]{1, 2, 1, 3}));
        var second = new TokenDataset.Window(3, LanguageModelExample.fromTokens(new int[]{2, 3, 2, 1}));
        var batch = new TokenDataset.Batch(List.of(first, second));
        var a = model.backward(first.example()); var b = model.backward(second.example());
        var combined = SgdTrainer.batchGradient(model, batch);
        assertEquals(6, combined.supervisedTokens());
        assertEquals((a.loss() + b.loss()) / 2, combined.loss(), 1e-15);
        for (String name : model.parameters().keySet()) {
            Matrix g = combined.gradients().get(name), x = a.gradients().get(name), y = b.gradients().get(name);
            for (int row = 0; row < g.rows(); row++) for (int col = 0; col < g.columns(); col++)
                assertEquals((x.get(row, col) + y.get(row, col)) / 2,
                        g.get(row, col), 1e-15, name);
        }
        double independent = (SgdTrainer.evaluate(model, List.of(first))
                + SgdTrainer.evaluate(model, List.of(second))) / 2;
        assertEquals(independent, SgdTrainer.batchLoss(model, batch), 1e-15);
        assertEquals(combined.loss(), SgdTrainer.batchLoss(model, batch), 1e-15);
        assertBitsEqual(before, model);
        assertThrows(IllegalArgumentException.class, () -> new TokenDataset.Batch(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TokenDataset.Batch(List.of(first,
                new TokenDataset.Window(0, LanguageModelExample.fromTokens(new int[]{1, 2})))));
        assertEquals(1, SgdTrainer.perplexity(0));
        assertEquals(4, SgdTrainer.perplexity(Math.log(4)), 1e-14);
        assertEquals(Double.POSITIVE_INFINITY, SgdTrainer.perplexity(1000));
        assertThrows(IllegalArgumentException.class, () -> SgdTrainer.perplexity(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new SgdTrainer.Config(4, 1, 1, 1, 0, .9, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new SgdTrainer.Config(4, 1, 1, 0, .01, .9, 100, 100));
    }

    @Test void tinyNaturalTextTrainsDeterministicallyAndValidationCannotUpdate() throws IOException {
        Path first = directory.resolve("a.txt"), second = directory.resolve("b.txt");
        String fixture;
        try (var input = getClass().getResourceAsStream("/brain/tiny-corpus.txt")) {
            assertNotNull(input);
            fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(first, fixture, StandardCharsets.UTF_8);
        Files.writeString(second, fixture, StandardCharsets.UTF_8);
        var config = new SgdTrainer.Config(4, 4, 4, 3, .02, .9, 4096, 4096);
        var model = new JadeLanguageModel(new BrainConfig(256, 4, 4, 2, 1, 8), 26167);
        var copy = new JadeLanguageModel(model.config(), model.parameters());
        var one = SgdTrainer.runFiles(model, TOKENIZER, List.of(second, first), config);
        var two = SgdTrainer.runFiles(copy, TOKENIZER, List.of(first, second), config);
        assertArrayEquals(one.corpus().tokenIds(), two.corpus().tokenIds());
        assertEquals(one.dataset().train().size(), two.dataset().train().size());
        assertWindowsEqual(one.dataset().train(), two.dataset().train());
        assertWindowsEqual(one.dataset().validation(), two.dataset().validation());
        var batches = TokenDataset.batches(one.dataset().train(), config.batchSize());
        var twinBatches = TokenDataset.batches(two.dataset().train(), config.batchSize());
        for (int i = 0; i < batches.size(); i++)
            assertWindowsEqual(batches.get(i).windows(), twinBatches.get(i).windows());
        assertEquals(one.epochs(), two.epochs());
        assertEquals(one.steps(), two.steps());
        assertTrue(one.finalTrainingLoss() < one.initialTrainingLoss());
        assertEquals(config.epochs() * TokenDataset.batches(one.dataset().train(), config.batchSize()).size(),
                one.totalOptimizerSteps());
        assertEquals((long) config.epochs() * one.dataset().train().size() * config.contextLength(),
                one.totalSupervisedTokens());
        for (String name : one.model().parameters().keySet()) {
            Matrix x = one.model().parameters().get(name), y = two.model().parameters().get(name);
            for (int row = 0; row < x.rows(); row++) assertArrayEquals(x.row(row), y.row(row));
        }
        assertEquals(model.parameters(), copy.parameters());
        for (var epoch : one.epochs()) {
            assertEquals(batches.size(), epoch.optimizerSteps());
            assertEquals((long) one.dataset().train().size() * config.contextLength(),
                    epoch.trainingSupervisedTokens());
            var epochSteps = one.steps().stream().filter(s -> s.epoch() == epoch.number()).toList();
            assertEquals(batches.size(), epochSteps.size());
            for (int i = 0; i < batches.size(); i++)
                assertEquals(batches.get(i).supervisedTokens(), epochSteps.get(i).supervisedTokens());
        }
        assertPartitionRanges(one.corpus().tokenIds(), one.dataset(), config.contextLength());
        Map<String, double[][]> beforeValidation = snapshot(one.model());
        double val = SgdTrainer.evaluate(one.model(), one.dataset().validation());
        assertEquals(one.finalValidationLoss(), val);
        assertBitsEqual(beforeValidation, one.model());
        System.out.printf("CORPUS_DEMO files=%d bytes=%d codePoints=%d tokens=%d split=%d trainTokens=%d valTokens=%d trainExamples=%d valExamples=%d batches=%d steps=%d supervised=%d%n",
                one.corpus().fileCount(), one.corpus().utf8Bytes(), one.corpus().codePoints(),
                one.corpus().tokenCount(), one.dataset().splitIndex(), one.dataset().trainTokens().length,
                one.dataset().validationTokens().length, one.dataset().train().size(),
                one.dataset().validation().size(), TokenDataset.batches(one.dataset().train(), config.batchSize()).size(),
                one.totalOptimizerSteps(), one.totalSupervisedTokens());
        System.out.printf("CORPUS_INITIAL train=%.17g validation=%.17g%n",
                one.initialTrainingLoss(), one.initialValidationLoss());
        for (var epoch : one.epochs()) System.out.printf(
                "CORPUS_EPOCH number=%d train=%.17g validation=%.17g trainPpl=%.17g valPpl=%.17g steps=%d%n",
                epoch.number(), epoch.trainingLoss(), epoch.validationLoss(),
                epoch.trainingPerplexity(), epoch.validationPerplexity(), epoch.optimizerSteps());
        int[] prompt = TOKENIZER.encode("ja");
        var beforeGeneration = new JadeTextGenerator(model, TOKENIZER, TokenSelector.greedy())
                .generate(prompt, 2);
        var afterGeneration = new JadeTextGenerator(one.model(), TOKENIZER, TokenSelector.greedy())
                .generate(prompt, 2);
        assertEquals(afterGeneration, new JadeTextGenerator(two.model(), TOKENIZER, TokenSelector.greedy())
                .generate(prompt, 2));
        System.out.println("CORPUS_GENERATION prompt=ja before=" + escaped(beforeGeneration.generatedText())
                + " after=" + escaped(afterGeneration.generatedText()));
        assertEquals(beforeGeneration, new JadeTextGenerator(copy, TOKENIZER, TokenSelector.greedy())
                .generate(prompt, 2));
    }

    private static Map<String, double[][]> snapshot(JadeLanguageModel model) {
        var result = new java.util.LinkedHashMap<String, double[][]>();
        model.parameters().forEach((name, matrix) -> result.put(name, matrix.toArray()));
        return result;
    }

    private static void assertBitsEqual(Map<String, double[][]> before, JadeLanguageModel model) {
        before.forEach((name, values) -> {
            Matrix after = model.parameters().get(name);
            for (int i = 0; i < values.length; i++) for (int j = 0; j < values[i].length; j++)
                assertEquals(Double.doubleToRawLongBits(values[i][j]),
                        Double.doubleToRawLongBits(after.get(i, j)), name);
        });
    }

    private static void assertWindowsEqual(List<TokenDataset.Window> a, List<TokenDataset.Window> b) {
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).start(), b.get(i).start());
            assertArrayEquals(a.get(i).example().inputTokenIds(), b.get(i).example().inputTokenIds());
            assertArrayEquals(a.get(i).example().targetTokenIds(), b.get(i).example().targetTokenIds());
        }
    }

    private static void assertPartitionRanges(int[] tokens, TokenDataset set, int context) {
        for (var windows : List.of(set.train(), set.validation())) {
            int base = windows == set.train() ? 0 : set.splitIndex();
            int end = base == 0 ? set.splitIndex() : tokens.length;
            for (var window : windows) {
                int start = base + window.start();
                assertTrue(start >= base && start + context < end);
                assertArrayEquals(java.util.Arrays.copyOfRange(tokens, start, start + context),
                        window.example().inputTokenIds());
                assertArrayEquals(java.util.Arrays.copyOfRange(tokens, start + 1, start + context + 1),
                        window.example().targetTokenIds());
            }
        }
    }

    private static String escaped(String value) {
        var out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) out.append(String.format("\\u%04X", (int) ch));
            else out.append(ch);
        }
        return out.toString();
    }
}
