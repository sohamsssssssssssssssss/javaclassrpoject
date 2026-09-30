package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.generation.EnglishOutputMetrics;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.ByteBpeTokenizer;
import com.jade.brain.training.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One-off Loop 5E execution harness; all model and optimizer work uses JADE classes. */
public final class FullTrainingRun {
    private static final String CORPUS_DIGEST = "1ee4db08e1af1b8f7540021801cc4c4774142bad20c67a76977d57174a41d6ba";
    private static final BrainConfig MODEL = new BrainConfig(1024, 32, 256, 4, 6, 1024);
    private static final long LIMIT_NANOS = TimeUnit.MINUTES.toNanos(30);

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[0].equals("preflight") || args[0].equals("run")))
            throw new IllegalArgumentException("preflight|run corpus-dir 5d-smoke-checkpoint output-dir");
        Path output = Path.of(args[3]);
        Files.createDirectories(output);
        var prepared = ScaleReadinessBenchmark.prepare(Path.of(args[1]));
        var manifest = prepared.manifest();
        var split = prepared.split();
        require(manifest.files().size() == 11 && manifest.skipped().isEmpty() && manifest.bytes() == 304963
                && CORPUS_DIGEST.equals(manifest.digest()), "5D corpus manifest differs");
        require(ScaleReadinessBenchmark.bytes(split.train()) == 244099
                && ScaleReadinessBenchmark.bytes(split.validation()) == 30632
                && ScaleReadinessBenchmark.bytes(split.test()) == 30232, "5D split differs");
        var tokenizer = ScaleReadinessBenchmark.fit(prepared, 1024);
        var encoded = EnglishCorpus.encode(manifest, split, ScaleReadinessBenchmark.LIMITS, tokenizer);
        var data = encoded.dataset(32, 32);
        var test = StoredDataset.windows(encoded.test(), 32, 32);
        require(tokenizer.vocabularySize() == 1024 && encoded.train().size() == 92507
                && encoded.validation().size() == 12111 && encoded.test().size() == 11980
                && data.train().size() == 2890 && data.validation().size() == 378 && test.size() == 374,
                "5D BPE encoding differs");
        require(MODEL.parameterCount() == 5251072, "5D architecture differs");
        var historical = new CorpusTrainer.Data(
                List.of(data.train().get(data.train().size() / 3), data.train().get(2 * data.train().size() / 3)),
                data.validation().subList(0, 2), data.identity(), data.utf8Bytes(), data.tokenCount());
        var historicalState = BrainCheckpoint.loadTraining(Path.of(args[2]), MODEL, tokenizer.model(), historical,
                ScaleReadinessBenchmark.config(4, 2, .001));
        require(historicalState.completedEpochs() == 3 && historicalState.optimizer().steps() == 3,
                "5D smoke checkpoint differs");
        long maxHeap = Runtime.getRuntime().maxMemory();
        require(maxHeap == 1536L * 1024 * 1024, "JVM must use -Xmx1536m");
        long free = Files.getFileStore(output).getUsableSpace();
        require(free >= 1_000_000_000L, "Insufficient checkpoint space");
        System.out.printf(Locale.ROOT,
                "PREFLIGHT PASS corpus=%s encoded=%s files=%d bytes=%d trainBytes=244099 valBytes=30632 testBytes=30232 vocab=%d trainTokens=%d valTokens=%d testTokens=%d trainWindows=%d valWindows=%d testWindows=%d parameters=%d maxHeap=%d freeDisk=%d 5dCheckpoint=PASS%n",
                manifest.digest(), encoded.identity(), manifest.files().size(), manifest.bytes(), tokenizer.vocabularySize(),
                encoded.train().size(), encoded.validation().size(), encoded.test().size(), data.train().size(),
                data.validation().size(), test.size(), MODEL.parameterCount(), maxHeap, free);
        if (args[0].equals("preflight")) return;
        run(output, prepared, tokenizer, encoded, data, test);
    }

    private static void run(Path output, ScaleReadinessBenchmark.Prepared prepared, ByteBpeTokenizer tokenizer,
                            EnglishCorpus.Encoded encoded, CorpusTrainer.Data data,
                            List<TokenDataset.Window> test) throws Exception {
        var config = ScaleReadinessBenchmark.config(1, 2, .001);
        var random = new JadeLanguageModel(MODEL, 26167);
        require(random.parameters().values().stream().mapToLong(m -> (long) m.rows() * m.columns()).sum() == 5251072,
                "Actual model tensor count differs");
        var sample = new ArrayList<TokenDataset.Window>();
        for (int i = 0; i < 32; i++) sample.add(data.train().get(i * data.train().size() / 32));
        double sampleBefore = SgdTrainer.evaluate(random, sample);
        var initial = CorpusTrainer.initial(random, data, config);
        require(Double.isFinite(sampleBefore) && Double.isFinite(initial.initialTrainingLoss())
                && Double.isFinite(initial.initialValidationLoss()), "Nonfinite initial loss");
        System.out.printf(Locale.ROOT,
                "BEFORE sampleTrainLoss=%.12f fullTrainLoss=%.12f validationLoss=%.12f validationPpl=%.12f sampleWindows=%d%n",
                sampleBefore, initial.initialTrainingLoss(), initial.initialValidationLoss(),
                SgdTrainer.perplexity(initial.initialValidationLoss()), sample.size());
        var probes = ScaleReadinessBenchmark.probes(prepared); // Selected before optimizer step.
        writeGeneration(output.resolve("generation-before.txt"), random, tokenizer);
        Path initialPath = output.resolve("jade-5m-initial.jade");
        BrainCheckpoint.saveTraining(initialPath, initial, tokenizer.model());
        var loadedInitial = BrainCheckpoint.loadTraining(initialPath, MODEL, tokenizer.model(), data, config);
        require(loadedInitial.completedEpochs() == 0 && loadedInitial.optimizer().steps() == 0
                && loadedInitial.corpusIdentity().equals(data.identity()), "Initial checkpoint reload differs");
        System.out.printf("INITIAL_CHECKPOINT path=%s bytes=%d reload=PASS%n", initialPath, Files.size(initialPath));

        var ordered = CorpusTrainer.trainingOrder(data.train(), config, 1);
        var batches = TokenDataset.batches(ordered, 2);
        require(batches.size() == 1445, "Optimizer step count differs");
        long started = System.nanoTime(), last = started, positions = 0, intervalPositions = 0;
        long maxObserved = usedHeap();
        double fastest = 0, slowest = Double.POSITIVE_INFINITY, lossSum = 0;
        int intervalSteps = 0;
        var optimizer = initial.optimizer();
        var watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "jade-5e-deadline"); thread.setDaemon(true); return thread;
        });
        var deadline = watchdog.schedule(() -> {
            System.err.println("FAIL: 30-minute hard training deadline reached");
            System.exit(124);
        }, 30, TimeUnit.MINUTES);
        try (var progress = Files.newBufferedWriter(output.resolve("training-progress.tsv"))) {
            progress.write("step\tpositions\trolling_loss\telapsed_seconds\tinterval_positions_per_second\tused_heap\tmax_heap\n");
            for (int i = 0; i < batches.size(); i++) {
                require(System.nanoTime() - started < LIMIT_NANOS, "Training wall-time deadline reached");
                // The exact same operations and deterministic order as CorpusTrainer.train.
                var gradient = SgdTrainer.batchGradient(optimizer.model(), batches.get(i));
                require(Double.isFinite(gradient.loss()), "Nonfinite batch loss");
                optimizer = config.optimizer().step(optimizer, gradient.gradients(), config.optimizerConfig());
                require(optimizer.steps() == i + 1, "Optimizer counter differs");
                positions += gradient.supervisedTokens();
                intervalPositions += gradient.supervisedTokens();
                lossSum += gradient.loss(); intervalSteps++;
                long used = usedHeap(); maxObserved = Math.max(maxObserved, used);
                if (used > Runtime.getRuntime().maxMemory() * .90) {
                    System.gc();
                    require(usedHeap() < Runtime.getRuntime().maxMemory() * .90, "Heap above 90% after GC");
                }
                if ((i + 1) % 50 == 0 || i + 1 == batches.size()) {
                    long now = System.nanoTime();
                    double elapsed = (now - started) / 1e9, intervalRate = intervalPositions / ((now - last) / 1e9);
                    fastest = Math.max(fastest, intervalRate); slowest = Math.min(slowest, intervalRate);
                    progress.write(String.format(Locale.ROOT, "%d\t%d\t%.12f\t%.6f\t%.6f\t%d\t%d%n",
                            i + 1, positions, lossSum / intervalSteps, elapsed, intervalRate, used, Runtime.getRuntime().maxMemory()));
                    progress.flush();
                    System.out.printf(Locale.ROOT, "PROGRESS step=%d positions=%d loss=%.6f elapsed=%.1fs rate=%.1f/s usedHeap=%d maxHeap=%d%n",
                            i + 1, positions, lossSum / intervalSteps, elapsed, intervalRate, used, Runtime.getRuntime().maxMemory());
                    last = now; intervalPositions = 0; intervalSteps = 0; lossSum = 0;
                }
            }
        } finally {
            deadline.cancel(false); watchdog.shutdownNow();
        }
        double epochSeconds = (System.nanoTime() - started) / 1e9;
        require(positions == 92480 && optimizer.steps() == 1445 && epochSeconds < 1800,
                "Epoch did not complete within locked budget");
        var finalState = new CorpusTrainer.State(optimizer, config, 1, positions,
                initial.initialTrainingLoss(), initial.initialValidationLoss(), data.identity());
        CorpusTrainer.validateResume(finalState, data, config);
        System.out.printf(Locale.ROOT,
                "EPOCH_COMPLETE steps=%d positions=%d seconds=%.6f averageRate=%.6f fastestInterval=%.6f slowestInterval=%.6f maxObservedHeap=%d maxJvmHeap=%d%n",
                optimizer.steps(), positions, epochSeconds, positions / epochSeconds, fastest, slowest,
                maxObserved, Runtime.getRuntime().maxMemory());

        double finalTrain = SgdTrainer.evaluate(finalState.model(), data.train());
        double finalValidation = SgdTrainer.evaluate(finalState.model(), data.validation());
        require(Double.isFinite(finalTrain) && Double.isFinite(finalValidation), "Nonfinite final evaluation");
        System.out.printf(Locale.ROOT, "AFTER trainLoss=%.12f trainPpl=%.12f validationLoss=%.12f validationPpl=%.12f%n",
                finalTrain, SgdTrainer.perplexity(finalTrain), finalValidation, SgdTrainer.perplexity(finalValidation));
        Path finalPath = output.resolve("jade-5m-epoch-1.jade");
        BrainCheckpoint.saveTraining(finalPath, finalState, tokenizer.model());
        var reloaded = BrainCheckpoint.loadTraining(finalPath, MODEL, tokenizer.model(), data, config);
        require(reloaded.completedEpochs() == 1 && reloaded.optimizer().steps() == 1445
                && reloaded.totalSupervisedTokens() == 92480 && reloaded.corpusIdentity().equals(data.identity()),
                "Final checkpoint counters/identity differ");
        require(Arrays.equals(finalState.model().nextTokenLogits(tokenizer.encode("the ")),
                reloaded.model().nextTokenLogits(tokenizer.encode("the "))), "Reloaded inference differs");
        require(EnglishLearningBenchmark.generation(finalState.model(), tokenizer, "the ")
                .equals(EnglishLearningBenchmark.generation(reloaded.model(), tokenizer, "the ")),
                "Reloaded generation differs");
        System.out.printf("FINAL_CHECKPOINT path=%s bytes=%d reload=PASS optimizer=PASS generation=PASS%n",
                finalPath, Files.size(finalPath));

        // The first aggregate test evaluation occurs only after the final checkpoint is verified.
        double testLoss = SgdTrainer.evaluate(reloaded.model(), test);
        System.out.printf(Locale.ROOT, "TEST evaluations=1 loss=%.12f perplexity=%.12f positions=%d noFurtherTraining=YES%n",
                testLoss, SgdTrainer.perplexity(testLoss), test.size() * 32L);
        writeGeneration(output.resolve("generation-after.txt"), reloaded.model(), tokenizer);
        try (var out = Files.newBufferedWriter(output.resolve("generalization-probes.tsv"))) {
            out.write("partition\tprefix\tloss_before\tloss_after\tgeneration_before\tgeneration_after\n");
            for (var probe : probes) {
                var before = EnglishLearningBenchmark.prefixLoss(random, tokenizer, probe.text());
                var after = EnglishLearningBenchmark.prefixLoss(reloaded.model(), tokenizer, probe.text());
                String prompt = ScaleReadinessBenchmark.prefix(probe.text(), 20);
                var beforeText = EnglishLearningBenchmark.generation(random, tokenizer, prompt).text();
                var afterText = EnglishLearningBenchmark.generation(reloaded.model(), tokenizer, prompt).text();
                out.write(probe.partition() + "\t" + ScaleReadinessBenchmark.escape(probe.text()) + "\t" + before + "\t" + after
                        + "\t" + ScaleReadinessBenchmark.escape(beforeText) + "\t" + ScaleReadinessBenchmark.escape(afterText) + "\n");
            }
        }
        System.out.println("COMPLETE noOptimizerStepAfterTest=YES");
    }

    private static void writeGeneration(Path path, JadeLanguageModel model, ByteBpeTokenizer tokenizer) throws IOException {
        try (var out = Files.newBufferedWriter(path)) {
            out.write("prompt\tgenerated_token_ids\tescaped_generated_text\tvalid_utf8\tprintable\tcontrols\tword_like\ttoken_reuse\timmediate_repetition\trepeated_word_trigrams\n");
            for (String prompt : ScaleReadinessBenchmark.PROMPTS) {
                var generated = EnglishLearningBenchmark.generation(model, tokenizer, prompt);
                EnglishOutputMetrics.Result m = EnglishOutputMetrics.measure(generated.text(), generated.ids());
                out.write(ScaleReadinessBenchmark.escape(prompt) + "\t" + generated.ids() + "\t"
                        + ScaleReadinessBenchmark.escape(generated.text()) + "\t" + generated.validUtf8() + "\t"
                        + m.printableRatio() + "\t" + m.controlRatio() + "\t" + m.wordLikeRatio() + "\t"
                        + m.tokenRepetition() + "\t" + m.immediateTokenRepetition() + "\t"
                        + m.repeatedWordTrigrams() + "\n");
            }
        }
    }

    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
}
