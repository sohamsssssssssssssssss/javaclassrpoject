package com.jade.brain.training;

import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.BrainTokenizer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Epoch-boundary resume over the verified Loop 5A corpus, windows, batch math and evaluation. */
public final class CorpusTrainer {
    private CorpusTrainer() {}

    public record Config(SgdTrainer.Config data, TrainingOptimizer optimizer,
                         TrainingConfig optimizerConfig, boolean shuffleTrainingExamples, long shuffleSeed) {
        public Config {
            Objects.requireNonNull(data); Objects.requireNonNull(optimizer); Objects.requireNonNull(optimizerConfig);
            if (data.learningRate() != optimizerConfig.learningRate())
                throw new IllegalArgumentException("Conflicting learning rates");
            if (optimizer == TrainingOptimizer.SGD && optimizerConfig.clipThreshold() != 0)
                throw new IllegalArgumentException("SGD path does not clip gradients");
        }

        /** Epochs are a total target; only that target and ingestion ceilings may differ on resume. */
        public boolean compatible(Config other) {
            return optimizer == other.optimizer && optimizerConfig.equals(other.optimizerConfig)
                    && shuffleTrainingExamples == other.shuffleTrainingExamples && shuffleSeed == other.shuffleSeed
                    && data.contextLength() == other.data.contextLength() && data.stride() == other.data.stride()
                    && data.batchSize() == other.data.batchSize() && data.trainFraction() == other.data.trainFraction();
        }
    }

    public record State(TrainingOptimizer.State optimizer, Config config, int completedEpochs,
                        long totalSupervisedTokens, double initialTrainingLoss,
                        double initialValidationLoss, String corpusIdentity) {
        public State {
            Objects.requireNonNull(optimizer); Objects.requireNonNull(config);
            if (completedEpochs < 0 || completedEpochs > config.data().epochs() || totalSupervisedTokens < 0
                    || optimizer.type() != config.optimizer()
                    || !Double.isFinite(initialTrainingLoss) || initialTrainingLoss < 0
                    || !Double.isFinite(initialValidationLoss) || initialValidationLoss < 0
                    || corpusIdentity == null || !corpusIdentity.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid corpus training state");
        }
        public JadeLanguageModel model() { return optimizer.model(); }
    }

    public record Result(State state, List<SgdTrainer.Epoch> epochs, List<SgdTrainer.Step> steps) {
        public Result { epochs = List.copyOf(epochs); steps = List.copyOf(steps); }
    }

    /** Immutable partition views; scaled callers provide lazy windows over immutable token storage. */
    public record Data(List<TokenDataset.Window> train, List<TokenDataset.Window> validation,
                       String identity, long utf8Bytes, long tokenCount) {
        public Data {
            Objects.requireNonNull(train); Objects.requireNonNull(validation);
            if (train.isEmpty() || validation.isEmpty() || train.size() > 1_000_000
                    || validation.size() > 1_000_000 || utf8Bytes < 1 || tokenCount < 2
                    || identity == null || !identity.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid training dataset views");
            train = java.util.Collections.unmodifiableList(train);
            validation = java.util.Collections.unmodifiableList(validation);
        }
    }

    public static TextCorpus load(List<Path> files, BrainTokenizer tokenizer, Config config) throws IOException {
        return TextCorpus.load(files, tokenizer, config.data().maxInputBytes(), config.data().maxTokens());
    }

    public static State initial(JadeLanguageModel model, TextCorpus corpus, Config config) {
        return initial(model, prepare(model, corpus, config), config);
    }

    public static State initial(JadeLanguageModel model, Data dataset, Config config) {
        validateData(model, dataset, config);
        return new State(config.optimizer().initial(model), config, 0, 0,
                SgdTrainer.evaluate(model, dataset.train()), SgdTrainer.evaluate(model, dataset.validation()),
                dataset.identity());
    }

    /** Canonical windows are never mutated; epoch seeds need no persisted RNG cursor. */
    public static List<TokenDataset.Window> trainingOrder(List<TokenDataset.Window> canonical,
                                                         Config config, int epoch) {
        if (epoch < 1) throw new IllegalArgumentException("Epoch must be positive");
        if (canonical.size() > 1_000_000) throw new IllegalArgumentException("Shuffle index count limit exceeded");
        if (!config.shuffleTrainingExamples()) return java.util.Collections.unmodifiableList(canonical);
        int[] ordered = new int[canonical.size()];
        for (int i = 0; i < ordered.length; i++) ordered[i] = i;
        if (config.shuffleTrainingExamples()) {
            // Java Random + explicit Fisher-Yates; overflow in seed mixing is intentional modulo 2^64.
            var random = new Random(config.shuffleSeed() + 0x9E3779B97F4A7C15L * epoch);
            for (int i = ordered.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1), previous = ordered[i]; ordered[i] = ordered[j]; ordered[j] = previous;
            }
        }
        return new AbstractList<>() {
            public int size() { return ordered.length; }
            public TokenDataset.Window get(int index) { return canonical.get(ordered[index]); }
        };
    }

    public static Result train(State state, TextCorpus corpus, Config config) {
        return train(state, prepare(state.model(), corpus, config), config);
    }

    public static Result train(State state, Data data, Config config) {
        return train(state, data, config, Long.MAX_VALUE);
    }

    public static Result train(State state, Data data, Config config, long budgetNanos) {
        if (budgetNanos < 1) throw new IllegalArgumentException("Training time budget must be positive");
        long started = System.nanoTime();
        var dataset = resumeDataset(state, data, config);
        long remainingSteps = Math.multiplyExact((long) config.data().epochs() - state.completedEpochs(),
                TokenDataset.batches(dataset.train(), config.data().batchSize()).size());
        if (remainingSteps > 1_000_000)
            throw new IllegalArgumentException("Training step-history limit exceeded; reduce epoch target");
        var history = new ArrayList<SgdTrainer.Epoch>();
        var steps = new ArrayList<SgdTrainer.Step>();
        var current = state.optimizer();
        long totalTokens = state.totalSupervisedTokens();
        for (int epoch = state.completedEpochs() + 1; epoch <= config.data().epochs(); epoch++) {
            var batches = TokenDataset.batches(trainingOrder(dataset.train(), config, epoch), config.data().batchSize());
            long epochTokens = 0;
            for (var batch : batches) {
                if (System.nanoTime() - started >= budgetNanos)
                    throw new IllegalStateException("Training wall-time budget exceeded; previous state is unchanged");
                var gradient = SgdTrainer.batchGradient(current.model(), batch);
                current = config.optimizer().step(current, gradient.gradients(), config.optimizerConfig());
                epochTokens = Math.addExact(epochTokens, gradient.supervisedTokens());
                steps.add(new SgdTrainer.Step(epoch, current.steps(), gradient.loss(), gradient.supervisedTokens()));
            }
            totalTokens = Math.addExact(totalTokens, epochTokens);
            double trainLoss = SgdTrainer.evaluate(current.model(), dataset.train());
            double valLoss = SgdTrainer.evaluate(current.model(), dataset.validation());
            history.add(new SgdTrainer.Epoch(epoch, trainLoss, valLoss, SgdTrainer.perplexity(trainLoss),
                    SgdTrainer.perplexity(valLoss), epochTokens,
                    (long) dataset.validation().size() * config.data().contextLength(), batches.size()));
        }
        return new Result(new State(current, config, config.data().epochs(), totalTokens,
                state.initialTrainingLoss(), state.initialValidationLoss(), state.corpusIdentity()), history, steps);
    }

    public static void validateResume(State state, TextCorpus corpus, Config config) {
        validateResume(state, prepare(state.model(), corpus, config), config);
    }

    public static void validateResume(State state, Data data, Config config) {
        resumeDataset(state, data, config);
    }

    private static Data resumeDataset(State state, Data data, Config config) {
        if (!state.config().compatible(config) || config.data().epochs() < state.completedEpochs()
                || !state.corpusIdentity().equals(data.identity()))
            throw new IllegalArgumentException("Incompatible continuation configuration/corpus");
        validateData(state.model(), data, config);
        var dataset = data;
        long perEpochTokens = (long) dataset.train().size() * config.data().contextLength();
        long batchesPerEpoch = TokenDataset.batches(dataset.train(), config.data().batchSize()).size();
        if (state.optimizer().steps() != Math.multiplyExact(batchesPerEpoch, state.completedEpochs())
                || state.totalSupervisedTokens() != Math.multiplyExact(perEpochTokens, state.completedEpochs()))
            throw new IllegalArgumentException("Checkpoint counters do not describe an epoch boundary");
        return dataset;
    }

    public static Data prepare(JadeLanguageModel model, TextCorpus corpus, Config config) {
        var data = config.data();
        if (data.contextLength() > model.config().contextLength() || corpus.utf8Bytes() > data.maxInputBytes()
                || corpus.tokenCount() > data.maxTokens()
                || !model.parameters().keySet().containsAll(config.optimizerConfig().noDecayParameters()))
            throw new IllegalArgumentException("Incompatible model/corpus limits or parameter exclusions");
        for (int id : corpus.tokenIds()) if (id < 0 || id >= model.config().vocabSize())
            throw new IllegalArgumentException("Corpus token outside model vocabulary");
        var set = TokenDataset.split(corpus.tokenIds(), data.contextLength(), data.stride(), data.trainFraction());
        return new Data(set.train(), set.validation(), corpusIdentity(corpus), corpus.utf8Bytes(), corpus.tokenCount());
    }

    private static void validateData(JadeLanguageModel model, Data data, Config config) {
        if (config.data().contextLength() > model.config().contextLength()
                || data.utf8Bytes() > config.data().maxInputBytes() || data.tokenCount() > config.data().maxTokens()
                || !model.parameters().keySet().containsAll(config.optimizerConfig().noDecayParameters())
                || data.train().getFirst().example().inputTokenIds().length != config.data().contextLength()
                || data.validation().getFirst().example().inputTokenIds().length != config.data().contextLength())
            throw new IllegalArgumentException("Incompatible model, dataset dimensions or corpus limits");
    }

    public static String corpusIdentity(TextCorpus corpus) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            var value = ByteBuffer.allocate(4);
            for (int id : corpus.tokenIds()) { value.clear(); value.putInt(id); hash.update(value.array()); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
