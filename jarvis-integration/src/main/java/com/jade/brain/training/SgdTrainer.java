package com.jade.brain.training;

import com.jade.brain.math.CrossEntropyLoss;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.BrainTokenizer;
import com.jade.brain.tokenizer.ByteBpeTokenizer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic, single-threaded, in-memory mini-batch SGD over local text. */
public final class SgdTrainer {
    private SgdTrainer() {}

    public record Config(int contextLength, int stride, int batchSize, int epochs,
                         double learningRate, double trainFraction, int maxInputBytes, int maxTokens) {
        public Config {
            if (contextLength < 1 || contextLength > 4096 || stride < 1 || batchSize < 1
                    || batchSize > 1024 || epochs < 1 || epochs > 1000
                    || !Double.isFinite(learningRate) || learningRate <= 0
                    || !Double.isFinite(trainFraction) || trainFraction <= 0 || trainFraction >= 1
                    || maxInputBytes < 1 || maxInputBytes > 64 * 1024 * 1024
                    || maxTokens < 2 || maxTokens > TokenStore.Chunked.MAX_TOKENS)
                throw new IllegalArgumentException("Invalid training configuration");
        }
    }
    public record BatchGradient(double loss, int supervisedTokens, Map<String, Matrix> gradients) {
        public BatchGradient {
            if (!Double.isFinite(loss) || loss < 0 || supervisedTokens < 1)
                throw new IllegalArgumentException("Invalid batch loss");
            gradients = Collections.unmodifiableMap(new LinkedHashMap<>(gradients));
        }
    }
    public record Epoch(int number, double trainingLoss, double validationLoss,
                        double trainingPerplexity, double validationPerplexity,
                        long trainingSupervisedTokens, long validationSupervisedTokens,
                        long optimizerSteps) {}
    public record Step(int epoch, long number, double trainingLoss, int supervisedTokens) {}
    public record Result(JadeLanguageModel model, TextCorpus corpus, TokenDataset dataset,
                         Config config, List<Epoch> epochs, List<Step> steps, double initialTrainingLoss,
                         double initialValidationLoss, long totalOptimizerSteps,
                         long totalSupervisedTokens) {
        public Result { epochs = List.copyOf(epochs); steps = List.copyOf(steps); }
        public int totalEpochs() { return epochs.size(); }
        public double finalTrainingLoss() { return epochs.getLast().trainingLoss(); }
        public double finalValidationLoss() { return epochs.getLast().validationLoss(); }
    }

    public static Result runFiles(JadeLanguageModel model, BrainTokenizer tokenizer,
                                  List<Path> files, Config config) throws IOException {
        Objects.requireNonNull(model); Objects.requireNonNull(tokenizer); Objects.requireNonNull(config);
        if (model.config().vocabSize() != tokenizer.vocabularySize())
            throw new IllegalArgumentException("Tokenizer/model vocabulary mismatch");
        TextCorpus corpus = TextCorpus.load(files, tokenizer, config.maxInputBytes(), config.maxTokens());
        return run(model, corpus, config);
    }

    public static Result run(JadeLanguageModel model, TextCorpus corpus, Config config) {
        Objects.requireNonNull(model); Objects.requireNonNull(corpus); Objects.requireNonNull(config);
        if (config.contextLength() > model.config().contextLength())
            throw new IllegalArgumentException("Training context exceeds model context");
        if (corpus.utf8Bytes() > config.maxInputBytes() || corpus.tokenCount() > config.maxTokens())
            throw new IllegalArgumentException("Corpus exceeds training limits");
        for (int id : corpus.tokenIds()) if (id < 0 || id >= model.config().vocabSize())
            throw new IllegalArgumentException("Corpus token outside model vocabulary");
        TokenDataset dataset = TokenDataset.split(corpus.tokenIds(), config.contextLength(),
                config.stride(), config.trainFraction());
        double initialTrain = evaluate(model, dataset.train());
        double initialValidation = evaluate(model, dataset.validation());
        var history = new ArrayList<Epoch>();
        var stepHistory = new ArrayList<Step>();
        var batches = TokenDataset.batches(dataset.train(), config.batchSize());
        long steps = 0, totalTokens = 0;
        JadeLanguageModel current = model;
        for (int epoch = 1; epoch <= config.epochs(); epoch++) {
            long epochTokens = 0;
            for (var batch : batches) {
                var gradient = batchGradient(current, batch);
                current = Sgd.step(current, gradient.gradients(), config.learningRate());
                epochTokens += gradient.supervisedTokens();
                steps++;
                stepHistory.add(new Step(epoch, steps, gradient.loss(), gradient.supervisedTokens()));
            }
            totalTokens += epochTokens;
            // Both evaluations are forward-only; the validation partition never reaches backward or SGD.
            double trainLoss = evaluate(current, dataset.train());
            double validationLoss = evaluate(current, dataset.validation());
            long validationTokens = dataset.validation().stream()
                    .mapToLong(w -> w.example().targetTokenIds().length).sum();
            history.add(new Epoch(epoch, trainLoss, validationLoss, perplexity(trainLoss),
                    perplexity(validationLoss), epochTokens, validationTokens, batches.size()));
        }
        return new Result(current, corpus, dataset, config, history, stepHistory, initialTrain,
                initialValidation, steps, totalTokens);
    }

    public static BatchGradient batchGradient(JadeLanguageModel model, TokenDataset.Batch batch) {
        Objects.requireNonNull(model); Objects.requireNonNull(batch);
        int total = batch.supervisedTokens();
        Map<String, double[][]> sums = new LinkedHashMap<>();
        model.parameters().forEach((name, p) -> sums.put(name, new double[p.rows()][p.columns()]));
        double weightedLoss = 0;
        for (var window : batch.windows()) {
            var backward = model.backward(window.example());
            int count = backward.supervisedTokenCount();
            weightedLoss += backward.loss() * count;
            for (var entry : sums.entrySet()) {
                Matrix gradient = backward.gradients().get(entry.getKey());
                double[][] sum = entry.getValue();
                for (int row = 0; row < gradient.rows(); row++)
                    for (int column = 0; column < gradient.columns(); column++)
                        sum[row][column] += gradient.get(row, column) * count;
            }
        }
        Map<String, Matrix> averaged = new LinkedHashMap<>();
        sums.forEach((name, sum) -> {
            for (double[] row : sum) for (int column = 0; column < row.length; column++)
                row[column] /= total;
            averaged.put(name, new Matrix(sum));
        });
        Gradients.validate(model, averaged);
        return new BatchGradient(weightedLoss / total, total, averaged);
    }

    /** Forward-only batch mean; never invokes backward or the optimizer. */
    public static double batchLoss(JadeLanguageModel model, TokenDataset.Batch batch) {
        Objects.requireNonNull(batch, "batch");
        return evaluate(model, batch.windows());
    }

    /** Forward-only mean loss over all supervised positions. */
    public static double evaluate(JadeLanguageModel model, List<TokenDataset.Window> windows) {
        if (windows == null || windows.isEmpty()) throw new IllegalArgumentException("Empty evaluation set");
        double totalLoss = 0; long tokens = 0;
        for (var window : windows) {
            var example = window.example();
            int[] targets = example.targetTokenIds();
            double loss = CrossEntropyLoss.mean(model.forward(example.inputTokenIds()),
                    targets, model.config().vocabSize());
            totalLoss += loss * targets.length;
            tokens += targets.length;
        }
        double mean = totalLoss / tokens;
        if (!Double.isFinite(mean)) throw new IllegalArgumentException("Non-finite evaluation loss");
        return mean;
    }

    /** Positive infinity explicitly represents perplexity beyond double range. */
    public static double perplexity(double meanLoss) {
        if (!Double.isFinite(meanLoss) || meanLoss < 0)
            throw new IllegalArgumentException("Invalid mean loss");
        return meanLoss > Math.log(Double.MAX_VALUE) ? Double.POSITIVE_INFINITY : Math.exp(meanLoss);
    }
}
