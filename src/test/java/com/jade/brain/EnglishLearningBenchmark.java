package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.generation.*;
import com.jade.brain.math.CrossEntropyLoss;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.*;
import com.jade.brain.training.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit offline experiment, deliberately outside automatic unit-test execution. */
public final class EnglishLearningBenchmark {
    static final String[] PROMPTS = {"the ", "this ", "what ", "jade ", "the computer ", "once upon "};
    static final String[] SENTENCES = {"JADE learns language from examples.", "Where did the spacecraft land?",
            "I don't think that's the correct answer.", "The temperature is 27.5 degrees.",
            "Can a small model learn useful patterns?"};
    static final EnglishCorpus.Limits LIMITS = new EnglishCorpus.Limits(1_048_576, 8_388_608, 4096, 128, 65_536, 2_000_000);
    static CorpusTrainer.Config config(int epochs) {
        return new CorpusTrainer.Config(new SgdTrainer.Config(32, 32, 4, epochs, .003, .9, 8_388_608, 6_000_000),
                TrainingOptimizer.ADAMW, new TrainingConfig(.003, .9, .999, 1e-8, .01, 1, Set.of()), true, 42);
    }
    record Candidate(int target, ByteBpeTokenizer tokenizer, EnglishCorpus.Encoded encoded, double bytesPerToken, double fitSeconds) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Supply corpus directory and report directory");
        Path output = Path.of(args[1]); Files.createDirectories(output);
        var manifest = EnglishCorpus.scan(Path.of(args[0]), LIMITS);
        var split = EnglishCorpus.split(manifest, .9, .05);
        var sample = EnglishCorpus.fittingText(manifest, split, LIMITS);
        StringBuilder manifestText = new StringBuilder("path\tbytes\tcodePoints\tsha256\n");
        for (var entry : manifest.files()) manifestText.append(entry.path()).append('\t').append(entry.bytes()).append('\t')
                .append(entry.codePoints()).append('\t').append(entry.digest()).append('\n');
        for (var entry : manifest.skipped()) manifestText.append("SKIPPED\t").append(entry.path()).append('\t').append(entry.reason()).append('\n');
        Files.writeString(output.resolve("manifest.tsv"), manifestText);
        System.out.printf(Locale.ROOT, "CORPUS files=%d skipped=%d bytes=%d codePoints=%d digest=%s fitBytes=%d%n",
                manifest.files().size(), manifest.skipped().size(), manifest.bytes(), manifest.codePoints(), manifest.digest(),
                sample.stream().mapToInt(s -> s.getBytes(StandardCharsets.UTF_8).length).sum());
        System.out.printf("SPLIT trainFiles=%d validationFiles=%d testFiles=%d%n", split.train().size(), split.validation().size(), split.test().size());
        var candidates = new ArrayList<Candidate>();
        for (int target : new int[]{512, 1024, 2048}) {
            long start = System.nanoTime();
            var tokenizer = new ByteBpeTokenizer(new BpeTrainer().train(sample, target));
            double time = seconds(start);
            var encoded = EnglishCorpus.encode(manifest, split, LIMITS, tokenizer);
            // Check every fixture line under the same encoding boundaries, including held-out text.
            for (var entry : manifest.files()) for (String line : Files.readAllLines(manifest.root().resolve(entry.path()))) {
                if (!tokenizer.decode(tokenizer.encode(line + "\n")).equals(line + "\n")) throw new AssertionError("Round trip failed");
            }
            double ratio = manifest.bytes() / (double) encoded.tokens();
            candidates.add(new Candidate(target, tokenizer, encoded, ratio, time));
            System.out.printf(Locale.ROOT, "BPE target=%d actual=%d train=%d validation=%d test=%d bytesPerToken=%.6f fitSeconds=%.6f roundTrip=PASS%n",
                    target, tokenizer.vocabularySize(), encoded.train().size(), encoded.validation().size(), encoded.test().size(), ratio, time);
        }
        // Maximize measured compression subject to >=8 context-32 windows in both holdouts.
        var chosen = candidates.stream().filter(c -> c.encoded().validation().size() >= 257
                && c.encoded().test().size() >= 257).max(Comparator.comparingDouble(Candidate::bytesPerToken))
                .orElseThrow(() -> new IllegalStateException("No vocabulary candidate leaves eight held-out windows"));
        var tokenizer = chosen.tokenizer(); var encoded = chosen.encoded(); var data = encoded.dataset(32, 32);
        System.out.printf("SELECTED target=%d actual=%d identity=%s trainWindows=%d valWindows=%d testWindows=%d%n", chosen.target(),
                tokenizer.vocabularySize(), encoded.identity(), data.train().size(), data.validation().size(), StoredDataset.windows(encoded.test(),32,32).size());
        for (String sentence : SENTENCES) {
            int bytes = sentence.getBytes(StandardCharsets.UTF_8).length; int[] ids = tokenizer.encode(sentence);
            System.out.printf(Locale.ROOT, "TOKENIZER text=%s bytes=%d tokens=%d bytesPerToken=%.6f roundTrip=%s%n", escape(sentence), bytes, ids.length,
                    bytes / (double) ids.length, tokenizer.decode(ids).equals(sentence) ? "PASS" : "FAIL");
        }
        var architecture = new BrainConfig(tokenizer.vocabularySize(), 32, 32, 4, 2, 128);
        var initial = new JadeLanguageModel(architecture, 26167);
        long scalarBytes = initial.parameterCount() * Double.BYTES;
        System.out.printf("MODEL vocab=%d context=32 embedding=32 heads=4 layers=2 ffn=128 parameters=%d parameterBytes=%d momentBytes=%d gradientBytes=%d%n",
                tokenizer.vocabularySize(), initial.parameterCount(), scalarBytes, 2 * scalarBytes, scalarBytes);
        // Independent smoke snapshot; it never participates in the experiment or baseline.
        long start = System.nanoTime(); var smoke = config(1).optimizer().initial(initial); long smokeTokens = 0;
        var batches = TokenDataset.batches(CorpusTrainer.trainingOrder(data.train(), config(1), 1), 4);
        for (int i = 0; i < Math.min(4, batches.size()); i++) {
            var gradient = SgdTrainer.batchGradient(smoke.model(), batches.get(i));
            smoke = config(1).optimizer().step(smoke, gradient.gradients(), config(1).optimizerConfig());
            smokeTokens += gradient.supervisedTokens();
            if (seconds(start) > 30) throw new IllegalStateException("Catastrophically slow smoke benchmark");
        }
        double smokeSeconds = seconds(start);
        System.out.printf(Locale.ROOT, "SMOKE batch=4 context=32 tokens=%d steps=%d seconds=%.6f tokensPerSecond=%.6f%n",
                smokeTokens, smoke.steps(), smokeSeconds, smokeTokens / smokeSeconds);
        // Estimate before running; hard per-call budget adds an independent runtime guard.
        double estimated = data.train().size() * 32.0 * 6 / (smokeTokens / smokeSeconds);
        if (estimated > 180) throw new IllegalStateException("Projected six-epoch training exceeds 180 seconds: " + estimated);
        var state = CorpusTrainer.initial(initial, data, config(6));
        var testWindows = StoredDataset.windows(encoded.test(), 32, 32);
        double initialTest = SgdTrainer.evaluate(initial, testWindows);
        System.out.printf(Locale.ROOT, "INITIAL trainLoss=%.12f valLoss=%.12f valPpl=%.12f testLoss=%.12f testPpl=%.12f%n",
                state.initialTrainingLoss(), state.initialValidationLoss(), SgdTrainer.perplexity(state.initialValidationLoss()), initialTest, SgdTrainer.perplexity(initialTest));
        var before = new LinkedHashMap<String, Generation>();
        for (String prompt : PROMPTS) before.put(prompt, generation(initial, tokenizer, prompt));
        Set<Integer> plausible = new TreeSet<>();
        for (String line : sample) for (int offset = line.indexOf("the "); offset >= 0; offset = line.indexOf("the ", offset + 4)) {
            String rest = line.substring(offset + 4); if (!rest.isEmpty()) plausible.add(tokenizer.encode(rest)[0]);
        }
        double beforeMass = mass(initial, tokenizer.encode("the "), plausible);
        String trainingPrefix = "the garden was quiet when anna arrived on monday morning.\n";
        String heldoutPrefix = "the greenhouse was quiet when tom arrived on monday morning.\n";
        double trainPrefixBefore = prefixLoss(initial, tokenizer, trainingPrefix), heldoutPrefixBefore = prefixLoss(initial, tokenizer, heldoutPrefix);
        long trainingStart = System.nanoTime();
        Path checkpoint = output.resolve("english-epoch-2.jade");
        for (int epoch = 1; epoch <= 6; epoch++) {
            start = System.nanoTime();
            long remaining = 240_000_000_000L - (System.nanoTime() - trainingStart);
            if (remaining <= 0) throw new IllegalStateException("Experiment wall-time budget exhausted");
            var result = CorpusTrainer.train(state, data, config(epoch), remaining); state = result.state();
            var e = result.epochs().getFirst();
            System.out.printf(Locale.ROOT, "EPOCH epoch=%d trainLoss=%.12f valLoss=%.12f trainPpl=%.12f valPpl=%.12f steps=%d cumulativeSteps=%d tokens=%d seconds=%.6f%n",
                    epoch, e.trainingLoss(), e.validationLoss(), e.trainingPerplexity(), e.validationPerplexity(), e.optimizerSteps(), state.optimizer().steps(), e.trainingSupervisedTokens(), seconds(start));
            if (epoch == 2) {
                BrainCheckpoint.saveTraining(checkpoint, state, tokenizer.model());
                var resumed = BrainCheckpoint.loadTraining(checkpoint, architecture, tokenizer.model(), data, config(6));
                for (String name : state.model().parameters().keySet()) {
                    if (!Arrays.deepEquals(state.model().parameters().get(name).toArray(), resumed.model().parameters().get(name).toArray()))
                        throw new AssertionError("Checkpoint parameter mismatch");
                }
                if (state.optimizer().steps() != resumed.optimizer().steps()) throw new AssertionError("Checkpoint step mismatch");
                state = resumed;
                System.out.printf("CHECKPOINT saved=%s bytes=%d resumedEpoch=%d integration=PASS%n", checkpoint.getFileName(), Files.size(checkpoint), state.completedEpochs());
            }
        }
        // Freeze: this is the sole trained-model test evaluation, and no updates follow it.
        var trained = state.model(); double testLoss = SgdTrainer.evaluate(trained, testWindows);
        System.out.printf(Locale.ROOT, "TEST loss=%.12f ppl=%.12f supervisedTokens=%d lossReduction=%.12f pplReduction=%.12f%n",
                testLoss, SgdTrainer.perplexity(testLoss), testWindows.size() * 32L, initialTest - testLoss, SgdTrainer.perplexity(initialTest) - SgdTrainer.perplexity(testLoss));
        long[] beforeStats = new long[4], afterStats = new long[4];
        for (String prompt : PROMPTS) {
            var after = generation(trained, tokenizer, prompt);
            if (!after.equals(generation(trained, tokenizer, prompt))) throw new AssertionError("Non-deterministic generation");
            System.out.printf("GENERATION prompt=%s beforeIds=%s before=%s beforeUtf8=%s afterIds=%s after=%s afterUtf8=%s%n",
                    escape(prompt), before.get(prompt).ids(), escape(before.get(prompt).text()), before.get(prompt).validUtf8(),
                    after.ids(), escape(after.text()), after.validUtf8());
            outputStats(before.get(prompt).text(), beforeStats); outputStats(after.text(), afterStats);
        }
        System.out.printf(Locale.ROOT, "PROBES continuationIds=%s massBefore=%.12f massAfter=%.12f printableBefore=%.6f printableAfter=%.6f whitespaceBefore=%.6f whitespaceAfter=%.6f controlBefore=%.6f controlAfter=%.6f%n",
                plausible, beforeMass, mass(trained, tokenizer.encode("the "), plausible), ratio(beforeStats,1), ratio(afterStats,1), ratio(beforeStats,2), ratio(afterStats,2), ratio(beforeStats,3), ratio(afterStats,3));
        System.out.printf(Locale.ROOT, "PREFIX training=%s lossBefore=%.12f lossAfter=%.12f heldout=%s lossBefore=%.12f lossAfter=%.12f%n",
                escape(trainingPrefix), trainPrefixBefore, prefixLoss(trained,tokenizer,trainingPrefix), escape(heldoutPrefix), heldoutPrefixBefore, prefixLoss(trained,tokenizer,heldoutPrefix));
        System.out.printf(Locale.ROOT,"COMPLETE trainingSeconds=%.6f totalSteps=%d totalSupervisedTokens=%d usefulGeneralEnglishClaim=NO%n",seconds(trainingStart),state.optimizer().steps(),state.totalSupervisedTokens());
    }

    record Generation(List<Integer> ids, String text, boolean validUtf8) {}
    static Generation generation(JadeLanguageModel model, ByteBpeTokenizer tokenizer, String prompt) {
        // Keep the generator's exact greedy/context semantics. A random byte model may emit invalid UTF-8;
        // retain all IDs and explicitly label replacement decoding rather than discarding that prompt.
        int[] context = tokenizer.encode(prompt); var ids = new ArrayList<Integer>();
        for (int i = 0; i < 12 && context.length < model.config().contextLength(); i++) {
            int next = TokenSelector.greedy().select(model.nextTokenLogits(context)); ids.add(next);
            context = Arrays.copyOf(context, context.length + 1); context[context.length - 1] = next;
        }
        int[] generated = ids.stream().mapToInt(Integer::intValue).toArray();
        try {
            var result = new JadeTextGenerator(model, tokenizer, TokenSelector.greedy()).generate(tokenizer.encode(prompt),12);
            if (!result.generatedTokenIds().equals(ids)) throw new AssertionError("Generator semantics differ");
            return new Generation(List.copyOf(ids), result.generatedText(), true);
        } catch (IllegalArgumentException invalidUtf8) {
            var bytes = new java.io.ByteArrayOutputStream(); for (int id : generated) bytes.writeBytes(tokenizer.model().vocabulary().token(id).bytes());
            return new Generation(List.copyOf(ids), new String(bytes.toByteArray(), StandardCharsets.UTF_8), false);
        }
    }
    static double prefixLoss(JadeLanguageModel model, ByteBpeTokenizer tokenizer, String text) {
        int[] ids = tokenizer.encode(text); ids = Arrays.copyOf(ids, Math.min(ids.length, model.config().contextLength() + 1));
        var example = com.jade.brain.objective.LanguageModelExample.fromTokens(ids);
        return CrossEntropyLoss.mean(model.forward(example.inputTokenIds()),example.targetTokenIds(),model.config().vocabSize());
    }
    static double mass(JadeLanguageModel model, int[] context, Set<Integer> ids) {
        double[] logits = model.nextTokenLogits(context); double maximum = Arrays.stream(logits).max().orElseThrow(), total=0, selected=0;
        for (int i=0;i<logits.length;i++) { double value=Math.exp(logits[i]-maximum); total+=value; if(ids.contains(i))selected+=value; }
        return selected/total;
    }
    static void outputStats(String text, long[] stats) {
        text.codePoints().forEach(cp -> { stats[0]++; if (!Character.isISOControl(cp) && cp != 0xfffd) stats[1]++;
            if (Character.isWhitespace(cp)) stats[2]++; if (Character.isISOControl(cp) && !Character.isWhitespace(cp)) stats[3]++; });
    }
    static double ratio(long[] stats,int index) { return stats[0]==0 ? 0 : stats[index]/(double)stats[0]; }
    static double seconds(long start) { return (System.nanoTime()-start)/1_000_000_000.0; }
    static String escape(String value) {
        var out = new StringBuilder("\""); value.codePoints().forEach(cp -> {
            if (cp == '"' || cp == '\\') out.append('\\').appendCodePoint(cp);
            else if (cp < 32 || cp > 126) out.append(String.format(Locale.ROOT,"\\u{%04x}",cp));
            else out.appendCodePoint(cp);
        }); return out.append('"').toString();
    }
}
