package com.jade.brain;

import com.jade.brain.config.*;
import com.jade.brain.generation.*;
import com.jade.brain.math.CrossEntropyLoss;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.*;
import com.jade.brain.training.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit research run. Child JVMs impose hard deadlines on this same Java engine. */
public final class ScaleReadinessBenchmark {
    static final EnglishCorpus.Limits LIMITS=EnglishLearningBenchmark.LIMITS;
    static final String[] PROMPTS={"the ","this ","what ","why ","how ","jade ","the computer ","once upon ","because ","if the "};
    static final String[] SENTENCES={"A narrow footbridge connects the orchard to the railway platform.",
            "Why does the kettle whistle before the water stops boiling?", "I can't attend on Thursday; could we meet at 09:45 instead?",
            "Save the document, close its window, and check the backup.","The sensor reports 18.7 degrees, although the room feels warmer."};
    record Prepared(EnglishCorpus.Manifest manifest,EnglishCorpus.Split split,List<String> fit) {}
    static Prepared prepare(Path root) throws Exception {
        var manifest=EnglishCorpus.scan(root,LIMITS);var split=EnglishCorpus.split(manifest,.82,.09);
        return new Prepared(manifest,split,EnglishCorpus.balancedFittingText(manifest,split,LIMITS));
    }
    static ByteBpeTokenizer fit(Prepared p,int vocabulary){return new ByteBpeTokenizer(new BpeTrainer().train(p.fit(),vocabulary));}
    static CorpusTrainer.Config config(int epochs,int batch,double lr) {
        return new CorpusTrainer.Config(new SgdTrainer.Config(32,32,batch,epochs,lr,.82,8_388_608,6_000_000),TrainingOptimizer.ADAMW,
                new TrainingConfig(lr,.9,.999,1e-8,.01,1,Set.of()),true,42);
    }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("scale")){scale(args);return;}
        if(args[0].equals("control")){control(args);return;}
        if(args.length!=4 || !args[0].equals("run"))throw new IllegalArgumentException("run corpus provenance report-directory");
        Path root=Path.of(args[1]),report=Path.of(args[3]);Files.createDirectories(report);
        Path artifacts=Files.createTempDirectory("jade-loop-5d-checkpoints-");
        var prepared=prepare(root);var manifest=prepared.manifest();var split=prepared.split();
        CorpusOverlapAudit.writeManifest(manifest,Path.of(args[2]),report.resolve("manifest.tsv"));
        var audit=CorpusOverlapAudit.audit(manifest,split);
        System.out.printf("CORPUS files=%d skipped=%d bytes=%d codePoints=%d digest=%s trainBytes=%d validationBytes=%d testBytes=%d fitBytes=%d%n",
                manifest.files().size(),manifest.skipped().size(),manifest.bytes(),manifest.codePoints(),manifest.digest(),bytes(split.train()),bytes(split.validation()),bytes(split.test()),
                prepared.fit().stream().mapToInt(s->s.getBytes(StandardCharsets.UTF_8).length).sum());
        System.out.println("OVERLAP "+audit);
        var encodings=new ArrayList<EnglishCorpus.Encoded>();
        double[] fitTimes=new double[2];
        for(int index=0;index<2;index++){
            int target=index==0?512:1024;long start=System.nanoTime();var tokenizer=fit(prepared,target);fitTimes[index]=seconds(start);
            var encoded=EnglishCorpus.encode(manifest,split,LIMITS,tokenizer);encodings.add(encoded);
            System.out.printf(Locale.ROOT,"BPE target=%d actual=%d train=%d val=%d test=%d bytesPerToken=%.6f fitSeconds=%.6f windows32=%s windows64=%s%n",
                    target,tokenizer.vocabularySize(),encoded.train().size(),encoded.validation().size(),encoded.test().size(),manifest.bytes()/(double)encoded.tokens(),fitTimes[index],windows(encoded,32),windows(encoded,64));
            for(String sentence:SENTENCES){int[] ids=tokenizer.encode(sentence);if(!sentence.equals(tokenizer.decode(ids)))throw new AssertionError("Roundtrip");
                System.out.printf(Locale.ROOT,"TOKENIZER vocab=%d text=%s bytes=%d tokens=%d ratio=%.6f lossless=PASS%n",target,escape(sentence),sentence.getBytes(StandardCharsets.UTF_8).length,ids.length,sentence.getBytes(StandardCharsets.UTF_8).length/(double)ids.length);}
        }
        // Selection uses validation compression/time, never test loss or generations.
        double improvement=1-encodings.get(1).validation().size()/(double)encodings.get(0).validation().size();
        int selected=improvement>=.15 && fitTimes[1]<=4*fitTimes[0]?1024:512;
        System.out.printf(Locale.ROOT,"SELECTED vocab=%d validationTokenReduction=%.6f rule=at-least-15-percent-validation-compression-and-fit-within-4x%n",selected,improvement);
        var architectures=List.of(new BrainConfig(512,32,32,4,2,128),ModelScalePlanner.closest(selected,32,250000,200000,350000),
                ModelScalePlanner.closest(selected,32,1000000,800000,1300000),ModelScalePlanner.closest(selected,32,5000000,4500000,5500000));
        String[] names={"58K","250K","1M","5M"};
        for(int i=0;i<architectures.size();i++){var c=architectures.get(i);System.out.println("PLAN model="+names[i]+" architecture="+c+" count="+c.parameterCount()+" memory="+ModelScalePlanner.memory(c,2));}
        // Probes and all prompts are fixed and written before any model training.
        Files.writeString(report.resolve("probes.txt"),probes(prepared).toString()+"\nPROMPTS="+Arrays.toString(PROMPTS)+"\n");
        boolean gate=true;
        for(int i=0;i<architectures.size();i++){
            var c=architectures.get(i);if(!gate){System.out.println("SKIP model="+names[i]+" reason=previous-scale-gate-failed");continue;}
            Path log=report.resolve("scale-"+names[i]+".txt");
            gate=child(List.of("scale",root.toString(),artifacts.toString(),names[i],Integer.toString(c.vocabSize()),Integer.toString(c.embeddingDimension()),Integer.toString(c.numberOfLayers())),log,90);
            System.out.print(Files.readString(log));System.out.println("SCALE_GATE model="+names[i]+" safe="+gate);
        }
        Path log=report.resolve("control-58K.txt");boolean control=child(List.of("control",root.toString(),artifacts.toString()),log,240);
        System.out.print(Files.readString(log));System.out.println("COMPLETE scaleGate="+gate+" control="+control+" checkpoints="+artifacts);
        if(!control)throw new IllegalStateException("58K control did not complete its hard deadline");
    }
    static boolean child(List<String> args,Path log,int seconds) throws Exception {
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx1536m","-cp",System.getProperty("java.class.path"),ScaleReadinessBenchmark.class.getName()));command.addAll(args);
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(seconds,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);Files.writeString(log,"\nHARD_TIMEOUT seconds="+seconds+" process-killed\n",StandardOpenOption.APPEND);return false;}
        return process.exitValue()==0;
    }
    static void scale(String[] args) throws Exception {
        Path root=Path.of(args[1]),artifacts=Path.of(args[2]);String name=args[3];int vocabulary=Integer.parseInt(args[4]),width=Integer.parseInt(args[5]),layers=Integer.parseInt(args[6]);
        var architecture=new BrainConfig(vocabulary,32,width,4,layers,4*width);var memory=ModelScalePlanner.memory(architecture,2);
        ModelScalePlanner.requireHeap(memory,Runtime.getRuntime().maxMemory());
        var prepared=prepare(root);var tokenizer=fit(prepared,vocabulary);
        var shared=EnglishCorpus.encode(prepared.manifest(),prepared.split(),LIMITS,tokenizer);
        var all=shared.dataset(32,32);
        // Same document positions; each model uses its own frozen tokenizer's valid token meanings.
        var representative=List.of(all.train().get(all.train().size()/3),all.train().get(2*all.train().size()/3));
        var micro=new CorpusTrainer.Data(representative,all.validation().subList(0,2),all.identity(),all.utf8Bytes(),all.tokenCount());
        long heapBefore=heap(),started=System.nanoTime();var model=new JadeLanguageModel(architecture,26167);
        long registry=model.parameters().values().stream().mapToLong(m->(long)m.rows()*m.columns()).sum();if(registry!=architecture.parameterCount())throw new AssertionError("Parameter planner mismatch");
        long tick=System.nanoTime();double loss=0;
        for(var window:representative){var example=window.example();loss+=CrossEntropyLoss.mean(model.forward(example.inputTokenIds()),example.targetTokenIds(),vocabulary)/representative.size();}
        double forward=seconds(tick);
        tick=System.nanoTime();var backward=SgdTrainer.batchGradient(model,TokenDataset.batches(representative,2).getFirst());double backwardTime=seconds(tick);
        var optimizer=TrainingOptimizer.ADAMW.initial(model);tick=System.nanoTime();optimizer=TrainingOptimizer.ADAMW.step(optimizer,backward.gradients(),config(1,2,.001).optimizerConfig());double adam=seconds(tick);
        if(!Double.isFinite(loss)||!Double.isFinite(backward.loss()))throw new AssertionError("Nonfinite scale loss");
        if(forward+backwardTime+adam>10)throw new IllegalStateException("Model measured step exceeded 10 seconds");
        long heapAfterStep=heap();
        System.out.printf(Locale.ROOT,"STEP_GATE model=%s forward=%.6f backwardIncludingForward=%.6f AdamW=%.6f trainingStep=%.6f supervisedTokens=64 tokensPerSecond=%.6f finite=PASS%n",name,forward,backwardTime,adam,backwardTime+adam,64/(backwardTime+adam));
        var initial=CorpusTrainer.initial(model,micro,config(3,2,.001));
        tick=System.nanoTime();var result=CorpusTrainer.train(initial,micro,config(3,2,.001),30_000_000_000L);double smokeTime=seconds(tick);
        Path checkpoint=artifacts.resolve(name+"-smoke.jade");BrainCheckpoint.saveTraining(checkpoint,result.state(),tokenizer.model());
        var resumed=BrainCheckpoint.loadTraining(checkpoint,architecture,tokenizer.model(),micro,config(4,2,.001));
        for(String key:result.state().model().parameters().keySet())if(!Arrays.deepEquals(result.state().model().parameters().get(key).toArray(),resumed.model().parameters().get(key).toArray()))throw new AssertionError("Resume parameters");
        var continued=CorpusTrainer.train(resumed,micro,config(4,2,.001),10_000_000_000L);
        var uninterrupted=CorpusTrainer.train(result.state(),micro,config(4,2,.001),10_000_000_000L);
        assertSameState(continued.state(),uninterrupted.state());
        double finalLoss=continued.epochs().getFirst().trainingLoss();
        if(!Double.isFinite(finalLoss)||Double.doubleToLongBits(finalLoss)==Double.doubleToLongBits(initial.initialTrainingLoss()))throw new AssertionError("Loss did not move");
        System.out.printf(Locale.ROOT,"SCALE model=%s parameters=%d context=32 batch=2 forward=%.6f backwardIncludingForward=%.6f AdamW=%.6f totalMeasuredStep=%.6f measuredStepTokens=64 measuredStepTokensPerSecond=%.6f smokeSteps=3 smokeTokens=192 smokeSecondsIncludingEvaluation=%.6f smokeTokensPerSecond=%.6f checkpointBytes=%d estimatedPeak=%d heapBefore=%d heapAfterStep=%d heapAfterSmoke=%d%n",
                name,registry,forward,backwardTime,adam,backwardTime+adam,64/(backwardTime+adam),smokeTime,192/smokeTime,Files.size(checkpoint),memory.estimatedPeak(),heapBefore,heapAfterStep,heap());
        System.out.printf(Locale.ROOT,"MICRO model=%s initialLoss=%.12f finalLoss=%.12f steps=4 tokens=256 wallSeconds=%.6f endToEndTokensPerSecond=%.6f checkpoint=%s checkpointBytes=%d resume=PASS finite=PASS%n",
                name,initial.initialTrainingLoss(),finalLoss,seconds(started),256/seconds(started),checkpoint,Files.size(checkpoint));
    }
    record Probe(String partition,String text) {}
    static void assertSameState(CorpusTrainer.State a,CorpusTrainer.State b) {
        if(a.optimizer().steps()!=b.optimizer().steps() || a.totalSupervisedTokens()!=b.totalSupervisedTokens())throw new AssertionError("Resume counters");
        for(String key:a.model().parameters().keySet()) {
            if(!Arrays.deepEquals(a.model().parameters().get(key).toArray(),b.model().parameters().get(key).toArray()))throw new AssertionError("Resume weights");
            var x=a.optimizer().adamw().moments().get(key);var y=b.optimizer().adamw().moments().get(key);
            if(!Arrays.deepEquals(x.first().toArray(),y.first().toArray()) || !Arrays.deepEquals(x.second().toArray(),y.second().toArray()))throw new AssertionError("Resume moments");
        }
    }
    static List<Probe> probes(Prepared p) throws Exception {
        var probes=new ArrayList<Probe>();
        for(var entry:List.of(p.split().train().getFirst(),p.split().train().get(1),p.split().train().getLast()))
            probes.add(new Probe("train",paragraphPrefixes(p.manifest().root().resolve(entry.path())).getFirst()));
        for(var group:List.of(p.split().validation(),p.split().test())){
            String kind=group==p.split().validation()?"validation":"test";var prefixes=paragraphPrefixes(p.manifest().root().resolve(group.getFirst().path()));
            for(int index:new int[]{0,2,4})probes.add(new Probe(kind,prefixes.get(index)));
        }
        for(String text:List.of("A patient astronomer checks the lens before measuring a distant star.","The bus arrived late because a fallen branch blocked the hill road.","If a sensor disagrees with a reference, record both values before adjusting it."))probes.add(new Probe("out-of-corpus",text));
        return List.copyOf(probes);
    }
    static List<String> paragraphPrefixes(Path path) throws Exception {
        // File is already limited to 1 MiB; probe selection is bounded and happens before training.
        return Arrays.stream(Files.readString(path).split("\\n\\s*\\n")).map(s->s.replaceAll("\\s+"," ").strip()).filter(s->s.length()>=100)
                .limit(20).map(s->prefix(s,80)).toList();
    }
    static void control(String[] args) throws Exception {
        var prepared=prepare(Path.of(args[1]));var tokenizer=fit(prepared,512);var encoded=EnglishCorpus.encode(prepared.manifest(),prepared.split(),LIMITS,tokenizer);
        var data=encoded.dataset(32,32);long perEpoch=data.train().size()*32L;int epochs=(int)Math.max(1,Math.min(3,240_000/perEpoch));
        if(perEpoch*epochs<100_000 || perEpoch*epochs>300_000)throw new IllegalArgumentException("Control token budget outside 100K-300K");
        var model=new JadeLanguageModel(new BrainConfig(512,32,32,4,2,128),26167);var state=CorpusTrainer.initial(model,data,config(epochs,4,.003));
        var before=new LinkedHashMap<String,EnglishLearningBenchmark.Generation>();for(String prompt:PROMPTS)before.put(prompt,EnglishLearningBenchmark.generation(model,tokenizer,prompt));
        var probes=probes(prepared);var prefixBefore=new HashMap<Probe,Double>();
        for(var probe:probes)if(!probe.partition().equals("test"))prefixBefore.put(probe,EnglishLearningBenchmark.prefixLoss(model,tokenizer,probe.text()));
        System.out.printf(Locale.ROOT,"CONTROL_INITIAL parameters=%d trainLoss=%.12f valLoss=%.12f valPpl=%.12f epochs=%d tokensPerEpoch=%d%n",model.parameterCount(),state.initialTrainingLoss(),state.initialValidationLoss(),SgdTrainer.perplexity(state.initialValidationLoss()),epochs,perEpoch);
        long start=System.nanoTime();
        for(int epoch=1;epoch<=epochs;epoch++){
            long tick=System.nanoTime();var result=CorpusTrainer.train(state,data,config(epoch,4,.003),230_000_000_000L-(System.nanoTime()-start));state=result.state();var e=result.epochs().getFirst();
            System.out.printf(Locale.ROOT,"CONTROL_EPOCH epoch=%d trainLoss=%.12f valLoss=%.12f trainPpl=%.12f valPpl=%.12f steps=%d tokens=%d seconds=%.6f%n",epoch,e.trainingLoss(),e.validationLoss(),e.trainingPerplexity(),e.validationPerplexity(),e.optimizerSteps(),e.trainingSupervisedTokens(),seconds(tick));
            if(epoch==1){Path checkpoint=Path.of(args[2]).resolve("58K-diverse-control.jade");BrainCheckpoint.saveTraining(checkpoint,state,tokenizer.model());state=BrainCheckpoint.loadTraining(checkpoint,model.config(),tokenizer.model(),data,config(epochs,4,.003));System.out.println("CONTROL_CHECKPOINT path="+checkpoint+" bytes="+Files.size(checkpoint)+" resume=PASS");}
        }
        var trained=state.model();double testLoss=SgdTrainer.evaluate(trained,StoredDataset.windows(encoded.test(),32,32));
        System.out.printf(Locale.ROOT,"CONTROL_TEST sealedUntilFinal=YES evaluations=1 loss=%.12f perplexity=%.12f supervisedTokens=%d%n",testLoss,SgdTrainer.perplexity(testLoss),StoredDataset.windows(encoded.test(),32,32).size()*32L);
        var beforeText=new StringBuilder();var afterText=new StringBuilder();var beforeIds=new ArrayList<Integer>();var afterIds=new ArrayList<Integer>();
        for(String prompt:PROMPTS){var after=EnglishLearningBenchmark.generation(trained,tokenizer,prompt);if(!after.equals(EnglishLearningBenchmark.generation(trained,tokenizer,prompt)))throw new AssertionError("Generation determinism");
            System.out.printf("GEN prompt=%s beforeIds=%s before=%s beforeUtf8=%s afterIds=%s after=%s afterUtf8=%s%n",escape(prompt),before.get(prompt).ids(),escape(before.get(prompt).text()),before.get(prompt).validUtf8(),after.ids(),escape(after.text()),after.validUtf8());
            beforeText.append(before.get(prompt).text()).append(' ');afterText.append(after.text()).append(' ');
            beforeIds.addAll(before.get(prompt).ids());afterIds.addAll(after.ids());
            System.out.println("PROMPT_METRICS prompt="+escape(prompt)+" before="+EnglishOutputMetrics.measure(before.get(prompt).text(),before.get(prompt).ids())+" after="+EnglishOutputMetrics.measure(after.text(),after.ids()));
        }
        System.out.println("QUALITY aggregateBefore="+EnglishOutputMetrics.measure(beforeText.toString(),beforeIds)+" aggregateAfter="+EnglishOutputMetrics.measure(afterText.toString(),afterIds)+" note=aggregate-token-boundaries-include-prompt-boundaries;per-prompt-metrics-are-primary");
        for(var probe:probes){double baseline=probe.partition().equals("test")?EnglishLearningBenchmark.prefixLoss(model,tokenizer,probe.text()):prefixBefore.get(probe);
            String prompt=prefix(probe.text(),20);var a=EnglishLearningBenchmark.generation(model,tokenizer,prompt);var b=EnglishLearningBenchmark.generation(trained,tokenizer,prompt);
            System.out.printf(Locale.ROOT,"PROBE partition=%s text=%s lossBefore=%.12f lossAfter=%.12f generationPrompt=%s before=%s after=%s beforeIds=%s afterIds=%s%n",probe.partition(),escape(probe.text()),baseline,EnglishLearningBenchmark.prefixLoss(trained,tokenizer,probe.text()),escape(prompt),escape(a.text()),escape(b.text()),a.ids(),b.ids());}
        System.out.printf(Locale.ROOT,"CONTROL_COMPLETE steps=%d tokens=%d wallSeconds=%.6f tokensPerSecond=%.6f%n",state.optimizer().steps(),state.totalSupervisedTokens(),seconds(start),state.totalSupervisedTokens()/seconds(start));
    }
    static String prefix(String text,int points){return text.substring(0,text.offsetByCodePoints(0,Math.min(points,text.codePointCount(0,text.length()))));}
    static List<Integer> windows(EnglishCorpus.Encoded e,int context){return List.of(StoredDataset.windows(e.train(),context,context).size(),StoredDataset.windows(e.validation(),context,context).size(),StoredDataset.windows(e.test(),context,context).size());}
    static long bytes(List<EnglishCorpus.Entry> entries){return entries.stream().mapToLong(EnglishCorpus.Entry::bytes).sum();}
    static long heap(){return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();}
    static double seconds(long tick){return EnglishLearningBenchmark.seconds(tick);}
    static String escape(String text){return EnglishLearningBenchmark.escape(text);}
}
