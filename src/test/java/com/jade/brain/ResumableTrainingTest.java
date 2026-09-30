package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.generation.JadeTextGenerator;
import com.jade.brain.generation.TokenSelector;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.*;
import com.jade.brain.training.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResumableTrainingTest {
    @TempDir Path directory;
    private static final BpeTokenizerModel TOKENIZER_MODEL = new BpeTokenizerModel(BpeVocabulary.base(), List.of());
    private static final ByteBpeTokenizer TOKENIZER = new ByteBpeTokenizer(TOKENIZER_MODEL);
    private static final BrainConfig ARCHITECTURE = new BrainConfig(256, 4, 4, 2, 1, 8);
    private static final TrainingConfig ADAM = new TrainingConfig(.005, .9, .999, 1e-8, .01, 0, Set.of());

    private static CorpusTrainer.Config config(int epochs, boolean shuffle, long seed, TrainingOptimizer type) {
        double lr = type == TrainingOptimizer.SGD ? .02 : ADAM.learningRate();
        return new CorpusTrainer.Config(new SgdTrainer.Config(4, 4, 4, epochs, lr, .9, 4096, 4096),
                type, type == TrainingOptimizer.SGD ? new TrainingConfig(lr, .9, .999, 1e-8, 0, 0, Set.of()) : ADAM,
                shuffle, seed);
    }

    private TextCorpus corpus() throws IOException {
        String text;
        try (var input = getClass().getResourceAsStream("/brain/tiny-corpus.txt")) {
            assertNotNull(input); text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path a = directory.resolve("a.txt"), b = directory.resolve("b.txt");
        Files.writeString(a, text, StandardCharsets.UTF_8); Files.writeString(b, text, StandardCharsets.UTF_8);
        return CorpusTrainer.load(List.of(b, a), TOKENIZER, config(4, true, 42, TrainingOptimizer.ADAMW));
    }

    private static Map<String, Matrix> uniform(JadeLanguageModel model, double value) {
        var result = new LinkedHashMap<String, Matrix>();
        model.parameters().forEach((name, matrix) -> {
            double[][] cells = matrix.toArray(); for (double[] row : cells) Arrays.fill(row, value);
            result.put(name, new Matrix(cells));
        });
        return result;
    }

    private static void bits(double a, double b) {
        assertEquals(Double.doubleToRawLongBits(a), Double.doubleToRawLongBits(b));
    }
    private static void sameMatrix(Matrix a, Matrix b) {
        assertEquals(a.rows(), b.rows()); assertEquals(a.columns(), b.columns());
        for (int i = 0; i < a.rows(); i++) for (int j = 0; j < a.columns(); j++) bits(a.get(i, j), b.get(i, j));
    }
    private static void sameOptimizer(TrainingOptimizer.State a, TrainingOptimizer.State b) {
        assertEquals(a.type(), b.type()); assertEquals(a.steps(), b.steps());
        assertEquals(a.model().config(), b.model().config());
        assertEquals(new ArrayList<>(a.model().parameters().keySet()), new ArrayList<>(b.model().parameters().keySet()));
        for (String name : a.model().parameters().keySet()) {
            sameMatrix(a.model().parameters().get(name), b.model().parameters().get(name));
            if (a.adamw() != null) {
                sameMatrix(a.adamw().moments().get(name).first(), b.adamw().moments().get(name).first());
                sameMatrix(a.adamw().moments().get(name).second(), b.adamw().moments().get(name).second());
            }
        }
        if (a.adamw() != null) assertEquals(a.adamw().step(), b.adamw().step());
    }

    @Test void manualFirstAndSecondAdamwStepsCheckEveryScalar() {
        var shape = new JadeLanguageModel(new BrainConfig(3, 3, 2, 1, 1, 3), 26167);
        var model = new JadeLanguageModel(shape.config(), uniform(shape, 2));
        var config = new TrainingConfig(.1, .5, .75, .01, .1, 0, Set.of());
        var initial = AdamW.State.initial(model);
        var first = AdamW.step(model, uniform(model, .2), initial, config);
        var second = AdamW.step(first.model(), uniform(model, -.4), first.optimizer(), config);
        // Independent scalar fixture: m1=.1, v1=.01; m2=-.15, v2=.0475.
        // Bias divisors: (.5,.25) at t=1 and (.75,.4375) at t=2.
        double expectedFirst = 2 - .1 * .2 / (.2 + .01) - .01 * 2;
        double expectedSecond = expectedFirst - .1 * (-.15 / .75)
                / (Math.sqrt(.0475 / .4375) + .01) - .01 * expectedFirst;
        for (String name : model.parameters().keySet()) {
            var w = model.parameters().get(name);
            for (int i = 0; i < w.rows(); i++) for (int j = 0; j < w.columns(); j++) {
                assertEquals(expectedFirst, first.model().parameters().get(name).get(i, j), 1e-15);
                assertEquals(expectedSecond, second.model().parameters().get(name).get(i, j), 1e-15);
                assertEquals(.1, first.optimizer().moments().get(name).first().get(i, j), 1e-15);
                assertEquals(.01, first.optimizer().moments().get(name).second().get(i, j), 1e-15);
                assertEquals(-.15, second.optimizer().moments().get(name).first().get(i, j), 1e-15);
                assertEquals(.0475, second.optimizer().moments().get(name).second().get(i, j), 1e-15);
            }
        }
        assertEquals(1, first.optimizer().step()); assertEquals(2, second.optimizer().step());
        assertEquals(0, initial.step());
        System.out.printf("ADAMW_MANUAL first=%.17g second=%.17g tolerance=1e-15%n", expectedFirst, expectedSecond);
    }

    @Test void zeroGradientsDecayAndExistingMomentum() {
        var shape = new JadeLanguageModel(new BrainConfig(3, 3, 2, 1, 1, 3), 26167);
        var model = new JadeLanguageModel(shape.config(), uniform(shape, 2));
        var config = new TrainingConfig(.1, .5, .75, .01, .1, 0, Set.of("embedding.weight"));
        var first = AdamW.step(model, uniform(model, 0), AdamW.State.initial(model), config);
        for (String name : model.parameters().keySet()) {
            var matrix = first.model().parameters().get(name);
            for (int i = 0; i < matrix.rows(); i++) for (int j = 0; j < matrix.columns(); j++) {
                assertEquals(name.equals("embedding.weight") ? 2 : 1.98, matrix.get(i, j), 1e-15);
                bits(0, first.optimizer().moments().get(name).first().get(i, j));
                bits(0, first.optimizer().moments().get(name).second().get(i, j));
            }
        }
        var noDecay = new TrainingConfig(.1, .5, .75, .01, 0, 0, Set.of());
        var unchanged = AdamW.step(model, uniform(model, 0), AdamW.State.initial(model), noDecay);
        for (String name : model.parameters().keySet()) sameMatrix(model.parameters().get(name), unchanged.model().parameters().get(name));
        var seeded = AdamW.step(model, uniform(model, .2), AdamW.State.initial(model), noDecay);
        var zero = AdamW.step(seeded.model(), uniform(model, 0), seeded.optimizer(), noDecay);
        assertEquals(.05, zero.optimizer().moments().get("lmHead.weight").first().get(0, 0), 1e-15);
        assertEquals(.0075, zero.optimizer().moments().get("lmHead.weight").second().get(0, 0), 1e-15);
        assertNotEquals(seeded.model().parameters().get("lmHead.weight").get(0, 0), zero.model().parameters().get("lmHead.weight").get(0, 0));
    }

    @Test void invalidHyperparametersAndNonfiniteGradientsRejectAtomically() {
        double[] valid = {.005, .9, .999, 1e-8, .01};
        double[][] invalid = {{0, -1}, {-1, 1}, {-1, 1}, {0, -1}, {-1}};
        for (int field = 0; field < valid.length; field++) {
            var values = new ArrayList<Double>(); for (double v : invalid[field]) values.add(v);
            values.add(Double.NaN); values.add(Double.POSITIVE_INFINITY); values.add(Double.NEGATIVE_INFINITY);
            for (double value : values) {
                double[] args = valid.clone(); args[field] = value;
                assertThrows(IllegalArgumentException.class,
                        () -> new TrainingConfig(args[0], args[1], args[2], args[3], args[4], 0, Set.of()));
            }
        }
        var model = new JadeLanguageModel(ARCHITECTURE, 26167);
        var state = TrainingOptimizer.ADAMW.initial(model);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> uniform(model, value));
        assertThrows(IllegalArgumentException.class,
                () -> TrainingOptimizer.ADAMW.step(state, uniform(model, Double.MAX_VALUE), ADAM));
        sameOptimizer(TrainingOptimizer.ADAMW.initial(new JadeLanguageModel(ARCHITECTURE, 26167)), state);
    }

    @Test void shuffleIsOwnedDeterministicDistinctAndDoesNotChangeCanonicalPartitions() throws IOException {
        var corpus = corpus(); var set = TokenDataset.split(corpus.tokenIds(), 4, 4, .9);
        var config = config(4, true, 42, TrainingOptimizer.ADAMW);
        var canonical = List.copyOf(set.train()); var validation = List.copyOf(set.validation());
        var a = CorpusTrainer.trainingOrder(canonical, config, 1);
        assertEquals(a, CorpusTrainer.trainingOrder(canonical, config, 1));
        assertNotEquals(a, CorpusTrainer.trainingOrder(canonical, config(4, true, 43, TrainingOptimizer.ADAMW), 1));
        assertNotEquals(a, CorpusTrainer.trainingOrder(canonical, config, 2));
        assertEquals(new HashSet<>(canonical), new HashSet<>(a));
        assertEquals(canonical.size(), a.size()); assertEquals(canonical, set.train()); assertEquals(validation, set.validation());
        assertEquals(canonical, CorpusTrainer.trainingOrder(canonical, config(4, false, 42, TrainingOptimizer.ADAMW), 1));
        assertThrows(IllegalArgumentException.class, () -> CorpusTrainer.trainingOrder(canonical, config, 0));
    }

    @Test void checkpointRoundTripAndResumeMatchUninterruptedRawBits() throws Exception {
        var corpus = corpus(); var fullConfig = config(4, true, 42, TrainingOptimizer.ADAMW);
        var initialModel = new JadeLanguageModel(ARCHITECTURE, 26167);
        var continuous = CorpusTrainer.train(CorpusTrainer.initial(initialModel, corpus, fullConfig), corpus, fullConfig);
        Path path = directory.resolve("resume.jade");
        // This scope ends before loading; continuation reconstructs all model/trainer objects from disk.
        long savedSteps, savedTokens; double savedInitialTrain, savedInitialVal;
        {
            var partialConfig = config(2, true, 42, TrainingOptimizer.ADAMW);
            var partial = CorpusTrainer.train(CorpusTrainer.initial(new JadeLanguageModel(ARCHITECTURE, 26167), corpus, partialConfig), corpus, partialConfig);
            assertEquals(continuous.epochs().subList(0, 2), partial.epochs());
            assertEquals(continuous.steps().subList(0, partial.steps().size()), partial.steps());
            BrainCheckpoint.saveTraining(path, partial.state(), TOKENIZER_MODEL);
            var roundTrip = BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, partialConfig);
            sameOptimizer(partial.state().optimizer(), roundTrip.optimizer());
            assertEquals(partial.state().completedEpochs(), roundTrip.completedEpochs());
            assertEquals(partial.state().totalSupervisedTokens(), roundTrip.totalSupervisedTokens());
            bits(partial.state().initialTrainingLoss(), roundTrip.initialTrainingLoss());
            bits(partial.state().initialValidationLoss(), roundTrip.initialValidationLoss());
            assertEquals(partial.state().config(), roundTrip.config());
            Path again = directory.resolve("same.jade"); BrainCheckpoint.saveTraining(again, roundTrip, TOKENIZER_MODEL);
            assertArrayEquals(Files.readAllBytes(path), Files.readAllBytes(again));
            savedSteps = roundTrip.optimizer().steps(); savedTokens = roundTrip.totalSupervisedTokens();
            savedInitialTrain = roundTrip.initialTrainingLoss(); savedInitialVal = roundTrip.initialValidationLoss();
        }
        var freshTokenizer = new ByteBpeTokenizer(new BpeTokenizerModel(BpeVocabulary.base(), List.of()));
        var loaded = BrainCheckpoint.loadTraining(path, new BrainConfig(256, 4, 4, 2, 1, 8), freshTokenizer.model(), corpus, fullConfig);
        assertEquals(2, loaded.completedEpochs()); assertEquals(savedSteps, loaded.optimizer().steps());
        assertEquals(savedTokens, loaded.totalSupervisedTokens());
        bits(savedInitialTrain, loaded.initialTrainingLoss()); bits(savedInitialVal, loaded.initialValidationLoss());
        var resumed = CorpusTrainer.train(loaded, corpus, fullConfig);
        sameOptimizer(continuous.state().optimizer(), resumed.state().optimizer());
        assertEquals(continuous.state().totalSupervisedTokens(), resumed.state().totalSupervisedTokens());
        assertEquals(continuous.state().completedEpochs(), resumed.state().completedEpochs());
        assertEquals(continuous.epochs().subList(2, 4), resumed.epochs());
        assertEquals(continuous.steps().subList((int) savedSteps, continuous.steps().size()), resumed.steps());
        var dataset = TokenDataset.split(corpus.tokenIds(), 4, 4, .9);
        bits(SgdTrainer.evaluate(continuous.state().model(), dataset.train()), SgdTrainer.evaluate(resumed.state().model(), dataset.train()));
        bits(SgdTrainer.evaluate(continuous.state().model(), dataset.validation()), SgdTrainer.evaluate(resumed.state().model(), dataset.validation()));
        var before = generate(initialModel, TOKENIZER); var after = generate(continuous.state().model(), TOKENIZER);
        var replay = generate(resumed.state().model(), freshTokenizer);
        assertEquals(after, replay);
        assertNotEquals(initialModel.parameters().get("lmHead.weight").get(0, 0), continuous.state().model().parameters().get("lmHead.weight").get(0, 0));
        System.out.printf("RESUME_PROOF N=4 K=2 remaining=2 bytes=%d steps=%d supervised=%d exactParameters=true exactMoments=true exactLosses=true exactGeneration=true%n",
                Files.size(path), resumed.state().optimizer().steps(), resumed.state().totalSupervisedTokens());
        for (var epoch : continuous.epochs()) {
            assertTrue(Double.isFinite(epoch.trainingLoss())); assertTrue(Double.isFinite(epoch.validationLoss()));
            System.out.printf("ADAMW_CORPUS epoch=%d train=%.17g validation=%.17g trainPpl=%.17g valPpl=%.17g%n",
                    epoch.number(), epoch.trainingLoss(), epoch.validationLoss(), epoch.trainingPerplexity(), epoch.validationPerplexity());
        }
        System.out.println("ADAMW_GENERATION before=" + escaped(before.generatedText()) + " ids=" + before.generatedTokenIds());
        System.out.println("ADAMW_GENERATION after=" + escaped(after.generatedText()) + " ids=" + after.generatedTokenIds());
        System.out.println("ADAMW_GENERATION resumed=" + escaped(replay.generatedText()) + " ids=" + replay.generatedTokenIds());
    }

    private static com.jade.brain.generation.GenerationResult generate(JadeLanguageModel model, ByteBpeTokenizer tokenizer) {
        return new JadeTextGenerator(model, tokenizer, TokenSelector.greedy()).generate(tokenizer.encode("ja"), 2);
    }
    private static String escaped(String value) {
        var out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c < 32 || c > 126 || c == '\\' || c == '"') out.append(String.format("\\u%04X", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    @Test void optimizerAdapterPreservesLoop5aSgdBitsAndResumesSgd() throws Exception {
        var corpus = corpus(); var config = config(3, false, 42, TrainingOptimizer.SGD);
        var old = SgdTrainer.run(new JadeLanguageModel(ARCHITECTURE, 26167), corpus, config.data());
        var model = new JadeLanguageModel(ARCHITECTURE, 26167);
        var adapted = CorpusTrainer.train(CorpusTrainer.initial(model, corpus, config), corpus, config);
        assertEquals(old.epochs(), adapted.epochs()); assertEquals(old.steps(), adapted.steps());
        bits(5.550088489256743, adapted.state().initialTrainingLoss());
        bits(5.286137571500380, adapted.epochs().getLast().trainingLoss());
        for (String name : model.parameters().keySet()) sameMatrix(old.model().parameters().get(name), adapted.state().model().parameters().get(name));
        var partialConfig = config(1, false, 42, TrainingOptimizer.SGD);
        var partial = CorpusTrainer.train(CorpusTrainer.initial(model, corpus, partialConfig), corpus, partialConfig);
        Path path = directory.resolve("sgd.jade"); BrainCheckpoint.saveTraining(path, partial.state(), TOKENIZER_MODEL);
        var loaded = BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, config);
        assertNull(loaded.optimizer().adamw());
        var resumed = CorpusTrainer.train(loaded, corpus, config);
        sameOptimizer(adapted.state().optimizer(), resumed.state().optimizer());
    }

    private CorpusTrainer.State trained(TextCorpus corpus) {
        var config = config(1, true, 42, TrainingOptimizer.ADAMW);
        return CorpusTrainer.train(CorpusTrainer.initial(new JadeLanguageModel(ARCHITECTURE, 26167), corpus, config), corpus, config).state();
    }

    @Test void architectureOptimizerCorpusAndTrainingConfigurationMismatchesReject() throws Exception {
        var corpus = corpus(); var state = trained(corpus); Path path = directory.resolve("mismatch.jade");
        BrainCheckpoint.saveTraining(path, state, TOKENIZER_MODEL);
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, new BrainConfig(256, 4, 2, 1, 1, 8), TOKENIZER_MODEL, corpus, state.config()));
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, config(4, true, 42, TrainingOptimizer.SGD)));
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, config(4, true, 43, TrainingOptimizer.ADAMW)));
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, config(4, false, 42, TrainingOptimizer.ADAMW)));
        int[] changed = corpus.tokenIds(); changed[0] ^= 1;
        var differentCorpus = new TextCorpus(changed, corpus.fileCount(), corpus.utf8Bytes(), corpus.codePoints());
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, differentCorpus, state.config()));
        var d = state.config().data();
        for (var data : List.of(new SgdTrainer.Config(3, 4, 4, 4, .005, .9, 4096, 4096),
                new SgdTrainer.Config(4, 2, 4, 4, .005, .9, 4096, 4096),
                new SgdTrainer.Config(4, 4, 2, 4, .005, .9, 4096, 4096),
                new SgdTrainer.Config(4, 4, 4, 4, .005, .8, 4096, 4096))) {
            var incompatible = new CorpusTrainer.Config(data, TrainingOptimizer.ADAMW, ADAM, true, 42);
            assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, incompatible));
        }
        var alteredAdam = new TrainingConfig(.005, .8, .999, 1e-8, .01, 0, Set.of());
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus,
                new CorpusTrainer.Config(d, TrainingOptimizer.ADAMW, alteredAdam, true, 42)));
        var permitted = new CorpusTrainer.Config(new SgdTrainer.Config(4, 4, 4, 5, .005, .9, 8192, 8192), TrainingOptimizer.ADAMW, ADAM, true, 42);
        assertEquals(1, BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, permitted).completedEpochs());
        sameOptimizer(state.optimizer(), trained(corpus).optimizer());
    }

    @Test void tokenizerIdentityRejectsSameVocabularySizeWithDifferentMerges() throws Exception {
        var a = new BpeTrainer().train(List.of("aaaa bbbb cccc dddd"), 264);
        var b = new BpeTrainer().train(List.of("xxxx yyyy zzzz wwww"), 264);
        assertEquals(a.vocabularySize(), b.vocabularySize());
        var tokenizer = new ByteBpeTokenizer(a); Path file = directory.resolve("merged.txt");
        Files.writeString(file, "jade learns words.\n".repeat(8));
        var config = config(1, true, 42, TrainingOptimizer.ADAMW);
        var corpus = CorpusTrainer.load(List.of(file), tokenizer, config);
        var architecture = new BrainConfig(a.vocabularySize(), 4, 4, 2, 1, 8);
        var state = CorpusTrainer.train(CorpusTrainer.initial(new JadeLanguageModel(architecture, 26167), corpus, config), corpus, config).state();
        Path path = directory.resolve("tokenizer.jade"); BrainCheckpoint.saveTraining(path, state, a);
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, architecture, b, corpus, config));
        assertThrows(IOException.class, () -> BrainCheckpoint.saveTraining(path, state, TOKENIZER_MODEL));
        sameOptimizer(state.optimizer(), BrainCheckpoint.loadTraining(path, architecture, a, corpus, config).optimizer());
    }

    private static void resign(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(hash, 0, bytes, bytes.length - 32, 32);
    }

    /** Offsets are derived by reading the explicit format, not by searching for floating-point bytes. */
    private static Map<String, Integer> offsets(byte[] bytes) throws IOException {
        var raw = new ByteArrayInputStream(bytes); var in = new DataInputStream(raw); var result = new HashMap<String, Integer>();
        in.skipNBytes(32); in.readUTF(); in.skipNBytes(40 + 48);
        int count = in.readInt(); for (int i = 0; i < count; i++) in.readUTF();
        in.skipNBytes(9); result.put("completed", bytes.length - raw.available()); in.skipNBytes(4);
        result.put("steps", bytes.length - raw.available()); in.skipNBytes(8);
        result.put("supervised", bytes.length - raw.available()); in.skipNBytes(8 + 16);
        in.readUTF(); in.skipNBytes(32); count = in.readInt();
        for (int i = 0; i < count; i++) {
            in.readUTF(); if (i == 0) result.put("dimensions", bytes.length - raw.available());
            int rows = in.readInt(), cols = in.readInt(); if (i == 0) result.put("parameter", bytes.length - raw.available());
            in.skipNBytes(8L * rows * cols);
        }
        in.skipNBytes(8); result.put("firstMoment", bytes.length - raw.available());
        in.skipNBytes(8L * ARCHITECTURE.vocabSize() * ARCHITECTURE.embeddingDimension() + 8);
        result.put("secondMoment", bytes.length - raw.available()); return result;
    }

    @Test void corruptTruncatedMalformedAndNonfiniteTrainingCheckpointsRejectWithoutMutation() throws Exception {
        var corpus = corpus(); var state = trained(corpus); Path path = directory.resolve("broken.jade");
        BrainCheckpoint.saveTraining(path, state, TOKENIZER_MODEL); byte[] original = Files.readAllBytes(path);
        var offsets = offsets(original); var live = new JadeLanguageModel(ARCHITECTURE, state.model().parameters());
        for (int length : new int[]{0, 20, original.length - 1}) {
            Files.write(path, Arrays.copyOf(original, length));
            assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, state.config()));
        }
        byte[] shortMatrix = Arrays.copyOf(original, offsets.get("parameter") + 8 + 32);
        resign(shortMatrix); Files.write(path, shortMatrix);
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, state.config()));
        byte[] corrupted = original.clone(); corrupted[100] ^= 1; Files.write(path, corrupted);
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, state.config()));
        for (int offset : new int[]{0, 4, 8, offsets.get("dimensions"), offsets.get("completed")}) {
            byte[] bytes = original.clone(); ByteBuffer.wrap(bytes).putInt(offset, Integer.MAX_VALUE); resign(bytes); Files.write(path, bytes);
            assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, state.config()));
        }
        for (String field : List.of("parameter", "firstMoment", "secondMoment"))
            for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                byte[] bytes = original.clone(); ByteBuffer.wrap(bytes).putDouble(offsets.get(field), value); resign(bytes); Files.write(path, bytes);
                assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, state.config()));
            }
        for (String field : List.of("steps", "supervised")) {
            byte[] bytes = original.clone(); ByteBuffer.wrap(bytes).putLong(offsets.get(field), 999); resign(bytes); Files.write(path, bytes);
            assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, state.config()));
        }
        byte[] negative = original.clone(); ByteBuffer.wrap(negative).putDouble(offsets.get("secondMoment"), -1); resign(negative); Files.write(path, negative);
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(path, ARCHITECTURE, TOKENIZER_MODEL, corpus, state.config()));
        for (String name : live.parameters().keySet()) sameMatrix(live.parameters().get(name), state.model().parameters().get(name));
    }

    @Test void oversizedCheckpointAndFailedSavePreserveLastValidSnapshot() throws Exception {
        var corpus = corpus(); var state = trained(corpus); Path path = directory.resolve("valid.jade");
        BrainCheckpoint.saveTraining(path, state, TOKENIZER_MODEL); byte[] original = Files.readAllBytes(path);
        assertThrows(IOException.class, () -> BrainCheckpoint.saveTraining(path, state,
                new BpeTrainer().train(List.of("hello jade"), 260)));
        assertArrayEquals(original, Files.readAllBytes(path));
        Path missingParent = directory.resolve("missing/valid.jade");
        assertThrows(IOException.class, () -> BrainCheckpoint.saveTraining(missingParent, state, TOKENIZER_MODEL));
        assertArrayEquals(original, Files.readAllBytes(path));
        BrainCheckpoint.saveTraining(path, state, TOKENIZER_MODEL);
        assertArrayEquals(original, Files.readAllBytes(path));
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".jade-checkpoint-"))); }
        Path huge = directory.resolve("huge.jade");
        try (var file = new RandomAccessFile(huge.toFile(), "rw")) { file.setLength(64L * 1024 * 1024 + 1); }
        assertThrows(IOException.class, () -> BrainCheckpoint.loadTraining(huge, ARCHITECTURE, TOKENIZER_MODEL, corpus, state.config()));
    }
}
