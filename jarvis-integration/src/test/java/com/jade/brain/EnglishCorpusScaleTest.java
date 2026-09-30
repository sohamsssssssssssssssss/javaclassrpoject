package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.*;
import com.jade.brain.training.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EnglishCorpusScaleTest {
    @TempDir Path root;
    static final ByteBpeTokenizer BASE = new ByteBpeTokenizer(new BpeTokenizerModel(BpeVocabulary.base(), List.of()));
    static final EnglishCorpus.Limits LIMITS = EnglishLearningBenchmark.LIMITS;
    static final String PROSE = "The careful reader checked the records, asked 12 questions, and didn't guess.\n";
    void threeFiles() throws Exception {
        Files.writeString(root.resolve("a.txt"), PROSE.repeat(8));
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/b.txt"), PROSE.repeat(8));
        Files.writeString(root.resolve("z.txt"), PROSE.repeat(8));
    }
    @Test void deterministicNestedManifestAndContentIdentity() throws Exception {
        threeFiles(); var a=EnglishCorpus.scan(root,LIMITS); var b=EnglishCorpus.scan(root,LIMITS);
        assertEquals(a,b); assertEquals(List.of("a.txt","nested/b.txt","z.txt"),a.files().stream().map(EnglishCorpus.Entry::path).toList());
        assertEquals(PROSE.length()*24L,a.bytes()); assertEquals(a.bytes(),a.codePoints());
        Files.writeString(root.resolve("z.txt"),PROSE.repeat(7)+"The answer changed after another careful check.\n");
        assertNotEquals(a.digest(),EnglishCorpus.scan(root,LIMITS).digest());
    }
    @Test void manifestIdentityIncludesPaths() throws Exception {
        threeFiles(); var a=EnglishCorpus.scan(root,LIMITS); Files.move(root.resolve("z.txt"),root.resolve("y.txt"));
        assertNotEquals(a.digest(),EnglishCorpus.scan(root,LIMITS).digest());
    }
    @Test void skipsGarbageBinaryInvalidUtf8EmptyAndUnsupported() throws Exception {
        threeFiles(); Files.write(root.resolve("binary.txt"),new byte[]{0,1,2,3});
        Files.write(root.resolve("bad.txt"),new byte[]{(byte)0xc3,40}); Files.writeString(root.resolve("empty.txt"),"");
        Files.writeString(root.resolve("garbage.txt"),"1234567890".repeat(10)); Files.writeString(root.resolve("other.bin"),PROSE);
        var manifest=EnglishCorpus.scan(root,LIMITS); assertEquals(3,manifest.files().size()); assertEquals(5,manifest.skipped().size());
        assertTrue(manifest.skipped().stream().anyMatch(s->s.reason().equals("invalid UTF-8")));
        assertTrue(manifest.skipped().stream().anyMatch(s->s.reason().equals("empty")));
        assertTrue(manifest.skipped().stream().anyMatch(s->s.reason().equals("disallowed control characters")));
    }
    @Test void punctuationDialogueNumbersAndUnicodeRemainLossless() throws Exception {
        threeFiles(); String text="\"I don't know,\" Anna said. The temperature was 27.5 degrees; café signs cost £12.\r\n";
        Files.writeString(root.resolve("z.txt"),text); var manifest=EnglishCorpus.scan(root,LIMITS);
        assertEquals(3,manifest.files().size()); var split=EnglishCorpus.split(manifest,.9,.05);
        var encoded=EnglishCorpus.encode(manifest,split,LIMITS,BASE);
        assertEquals(text,BASE.decode(encoded.test().range(0,(int)encoded.test().size())));
        assertEquals(text.codePointCount(0,text.length()),manifest.files().getLast().codePoints());
    }
    @Test void pathologicalLineIsSkippedWithoutGrowingBuffer() throws Exception {
        threeFiles(); Files.writeString(root.resolve("long.txt"),"a".repeat(20_000));
        var manifest=EnglishCorpus.scan(root,LIMITS); assertEquals(3,manifest.files().size());
        assertEquals("pathological line length",manifest.skipped().getFirst().reason());
    }
    @Test void oversizeFileAndAggregateBudgetFailBeforeEncoding() throws Exception {
        threeFiles(); var small=new EnglishCorpus.Limits(100,200,100,20,100,1000);
        assertThrows(IllegalArgumentException.class,()->EnglishCorpus.scan(root,small));
        for(String name:List.of("a.txt","nested/b.txt","z.txt"))Files.writeString(root.resolve(name),PROSE);
        assertThrows(IllegalArgumentException.class,()->EnglishCorpus.scan(root,small));
    }
    @Test void changedFileCannotBeEncodedAgainstOldManifest() throws Exception {
        threeFiles(); var manifest=EnglishCorpus.scan(root,LIMITS); var split=EnglishCorpus.split(manifest,.9,.05);
        Files.writeString(root.resolve("a.txt"),PROSE.repeat(9));
        assertThrows(java.io.IOException.class,()->EnglishCorpus.encode(manifest,split,LIMITS,BASE));
    }
    @Test void threeWaySplitPreservesMinimumAndRejectsInsufficientFiles() throws Exception {
        threeFiles(); var manifest=EnglishCorpus.scan(root,LIMITS); var split=EnglishCorpus.split(manifest,.9,.05);
        assertEquals(1,split.train().size());assertEquals(1,split.validation().size());assertEquals(1,split.test().size());
        assertThrows(IllegalArgumentException.class,()->EnglishCorpus.split(manifest,.95,.05));
        Files.delete(root.resolve("z.txt"));assertThrows(IllegalArgumentException.class,()->EnglishCorpus.split(EnglishCorpus.scan(root,LIMITS),.9,.05));
    }
    @Test void overlappingOrForgedSplitIsRejected() throws Exception {
        threeFiles();var manifest=EnglishCorpus.scan(root,LIMITS);var split=EnglishCorpus.split(manifest,.9,.05);
        var forged=new EnglishCorpus.Split(split.train(),split.train(),split.test(),.9,.05);
        assertThrows(IllegalArgumentException.class,()->EnglishCorpus.fittingText(manifest,forged,LIMITS));
        assertThrows(IllegalArgumentException.class,()->EnglishCorpus.encode(manifest,forged,LIMITS,BASE));
    }
    @Test void bpeFitUsesOnlyTrainingAndHeldoutsNeverMutateVocabulary() throws Exception {
        threeFiles(); String validation="Zebra quizzes bewilder curious readers.\n",test="Xylophone music surprises every quiet visitor.\n";
        Files.writeString(root.resolve("nested/b.txt"),validation);Files.writeString(root.resolve("z.txt"),test);
        var manifest=EnglishCorpus.scan(root,LIMITS);var split=EnglishCorpus.split(manifest,.9,.05);
        var sample=EnglishCorpus.fittingText(manifest,split,LIMITS);assertEquals(PROSE.repeat(8),String.join("",sample));
        var trained=new BpeTrainer().train(sample,300);var tokenizer=new ByteBpeTokenizer(trained);
        var merges=List.copyOf(trained.merges());var encoded=EnglishCorpus.encode(manifest,split,LIMITS,tokenizer);
        assertEquals(validation,tokenizer.decode(encoded.validation().range(0,(int)encoded.validation().size())));
        assertEquals(test,tokenizer.decode(encoded.test().range(0,(int)encoded.test().size())));
        assertEquals(merges,trained.merges());assertEquals(300,tokenizer.vocabularySize());
        Files.writeString(root.resolve("z.txt"),PROSE);var updated=EnglishCorpus.scan(root,LIMITS);
        assertEquals(sample,EnglishCorpus.fittingText(updated,EnglishCorpus.split(updated,.9,.05),LIMITS));
    }
    @Test void fitSamplingCeilingIsExplicitAndNeverTruncatesUtf8() throws Exception {
        threeFiles();var limits=new EnglishCorpus.Limits(1024,4096,4096,20,100,2000);
        var manifest=EnglishCorpus.scan(root,limits);var sample=EnglishCorpus.fittingText(manifest,EnglishCorpus.split(manifest,.9,.05),limits);
        assertEquals(List.of(PROSE),sample);assertTrue(sample.getFirst().getBytes(StandardCharsets.UTF_8).length<=100);
        assertThrows(IllegalArgumentException.class,()->new EnglishCorpus.Limits(1024,4096,4096,20,65_537,2000));
    }
    @Test void chunkBoundaryRangesAndSealing() {
        var builder=new TokenStore.Chunked.Builder(9000);int[] ids=new int[8201];for(int i=0;i<ids.length;i++)ids[i]=i;
        builder.append(ids);var store=builder.build();ids[4096]=-1;
        assertEquals(8201,store.size());assertEquals(4096,store.get(4096));assertArrayEquals(new int[]{4095,4096,4097},store.range(4095,3));
        int[] copy=store.range(0,3);copy[0]=999;assertEquals(0,store.get(0));
        assertThrows(IndexOutOfBoundsException.class,()->store.get(8201));assertThrows(IndexOutOfBoundsException.class,()->store.get(-1));
        assertThrows(IllegalStateException.class,()->builder.append(new int[]{1}));assertThrows(IllegalStateException.class,builder::build);
    }
    @Test void tokenAndWindowOverflowGuards() {
        assertThrows(IllegalArgumentException.class,()->new TokenStore.Chunked.Builder(Long.MAX_VALUE));
        var builder=new TokenStore.Chunked.Builder(2);builder.append(new int[]{1,2});
        assertThrows(IllegalArgumentException.class,()->builder.append(new int[]{3}));var store=builder.build();
        assertThrows(IllegalArgumentException.class,()->store.range(Long.MAX_VALUE,1));assertThrows(IllegalArgumentException.class,()->store.range(0,Integer.MAX_VALUE));
        TokenStore huge=new TokenStore(){public long size(){return Integer.MAX_VALUE;}public int get(long i){throw new AssertionError("Must reject before lookup");}};
        assertThrows(IllegalArgumentException.class,()->StoredDataset.windows(huge,32,1));
        assertThrows(IllegalArgumentException.class,()->StoredDataset.windows(store,Integer.MAX_VALUE,1));
        assertThrows(IllegalArgumentException.class,()->TokenDataset.batches(List.of(),Integer.MAX_VALUE));
    }
    @Test void windowsAreLazyAndOnlyReadRequestedRange() {
        int[] reads={0};TokenStore counted=new TokenStore(){public long size(){return 1_000_000;}public int get(long index){reads[0]++;return (int)(index%256);}};
        var windows=StoredDataset.windows(counted,32,32);assertEquals(0,reads[0]);assertEquals(31_249,windows.size());
        var last=windows.getLast();assertEquals(33,reads[0]);assertEquals(999936,last.start());assertEquals(32,last.example().targetTokenIds().length);
        assertThrows(IndexOutOfBoundsException.class,()->windows.get(windows.size()));
    }
    @Test void largerThanLegacyLimitMultiFilePipelineNoSplitCrossing() throws Exception {
        // >1 MiB, while every input line and BPE fitting sample remain bounded.
        Files.writeString(root.resolve("a.txt"),PROSE.repeat(10_000));
        Files.writeString(root.resolve("b.txt"),PROSE.repeat(10_000));
        Files.writeString(root.resolve("c.txt"),PROSE.repeat(10_000));
        var manifest=EnglishCorpus.scan(root,LIMITS);assertTrue(manifest.bytes()>1_048_576);
        var split=EnglishCorpus.split(manifest,.9,.05);var sample=EnglishCorpus.fittingText(manifest,split,LIMITS);
        assertTrue(sample.stream().mapToLong(s->s.getBytes(StandardCharsets.UTF_8).length).sum()<=65_536);
        var encoded=EnglishCorpus.encode(manifest,split,LIMITS,BASE);
        assertEquals(manifest.bytes(),encoded.tokens());
        for(var store:List.of(encoded.train(),encoded.validation(),encoded.test())){
            var windows=StoredDataset.windows(store,32,32);var last=windows.getLast();assertTrue(last.start()+32L<store.size());
            assertArrayEquals(store.range(last.start(),32),last.example().inputTokenIds());
            assertArrayEquals(store.range(last.start()+1,32),last.example().targetTokenIds());
            var finalBatch=TokenDataset.batches(windows,4).getLast();
            assertTrue(finalBatch.windows().size()>=1 && finalBatch.windows().size()<=4);
        }
        assertEquals(EnglishCorpus.encode(manifest,split,LIMITS,BASE).identity(),encoded.identity());
    }
    @Test void deterministicShuffleAndIncompleteFinalBatch() {
        var builder=new TokenStore.Chunked.Builder(200);builder.append(new int[161]);var windows=StoredDataset.windows(builder.build(),32,32);
        var one=CorpusTrainer.trainingOrder(windows,EnglishLearningBenchmark.config(2),1);
        var two=CorpusTrainer.trainingOrder(windows,EnglishLearningBenchmark.config(2),1);
        assertEquals(one.stream().map(TokenDataset.Window::start).toList(),two.stream().map(TokenDataset.Window::start).toList());
        assertEquals(5,windows.size());assertEquals(1,TokenDataset.batches(one,4).getLast().windows().size());
        assertEquals(List.of(0,32,64,96,128),windows.stream().map(TokenDataset.Window::start).toList());
    }
    @Test void impossibleModelDimensionsAndMajorBuffersRejected() {
        assertThrows(IllegalArgumentException.class,()->new BrainConfig(512,4096,4,4,2,16));
        assertThrows(IllegalArgumentException.class,()->new BrainConfig(65536,32,1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new BrainConfig(Integer.MAX_VALUE,32,32,4,2,128));
        assertThrows(IllegalArgumentException.class,()->new SgdTrainer.Config(32,32,Integer.MAX_VALUE,1,.003,.9,100,100));
    }
    @Test void directoryDepthAndFileCountAreBounded() throws Exception {
        threeFiles();var limits=new EnglishCorpus.Limits(1024,4096,4096,3,100,2000);
        Files.writeString(root.resolve("fourth.txt"),PROSE);
        assertThrows(IllegalArgumentException.class,()->EnglishCorpus.scan(root,limits));
        Path deep=root.resolve("one/two/three/four/five/six/seven/eight");Files.createDirectories(deep);
        Files.writeString(deep.resolve("hidden.txt"),PROSE);
        assertThrows(IllegalArgumentException.class,()->EnglishCorpus.scan(root,LIMITS));
    }
    @Test void excessiveStepHistoryFailsBeforeTraining() {
        TokenStore store=new TokenStore(){public long size(){return 1_000_000;}public int get(long index){return 32;}};
        var windows=StoredDataset.windows(store,32,32);
        var data=new CorpusTrainer.Data(windows,windows.subList(0,1),"0".repeat(64),1_000_000,1_000_000);
        var cfg=new CorpusTrainer.Config(new SgdTrainer.Config(32,32,1,1000,.003,.9,8_388_608,6_000_000),
                TrainingOptimizer.ADAMW,EnglishLearningBenchmark.config(1).optimizerConfig(),true,42);
        var model=new JadeLanguageModel(new BrainConfig(512,32,32,4,2,128),26167);
        var state=new CorpusTrainer.State(cfg.optimizer().initial(model),cfg,0,0,1,1,data.identity());
        var error=assertThrows(IllegalArgumentException.class,()->CorpusTrainer.train(state,data,cfg,1));
        assertTrue(error.getMessage().contains("step-history"));
    }
    @Test void scaledForwardBackwardBoundedTrainingEvaluationAndCheckpoint() throws Exception {
        var manifest=EnglishCorpus.scan(Path.of(getClass().getResource("/brain/english").toURI()),LIMITS);
        var split=EnglishCorpus.split(manifest,.9,.05);
        var tokenizer=new ByteBpeTokenizer(new BpeTrainer().train(EnglishCorpus.fittingText(manifest,split,LIMITS),512));
        var encoded=EnglishCorpus.encode(manifest,split,LIMITS,tokenizer);
        var architecture=new BrainConfig(512,32,32,4,2,128);var model=new JadeLanguageModel(architecture,26167);
        assertEquals(58_368,model.parameterCount());
        var data=encoded.dataset(32,160);var cfg=new CorpusTrainer.Config(new SgdTrainer.Config(32,160,4,1,.003,.9,8_388_608,6_000_000),
                TrainingOptimizer.ADAMW,EnglishLearningBenchmark.config(1).optimizerConfig(),true,42);
        var example=data.train().getFirst().example();assertEquals(512,model.forward(example.inputTokenIds())[0].length);
        var backward=model.backward(example);assertTrue(Double.isFinite(backward.loss()));
        assertEquals(model.parameterCount(),backward.gradients().values().stream().mapToLong(m->(long)m.rows()*m.columns()).sum());
        var initial=CorpusTrainer.initial(model,data,cfg);
        assertThrows(IllegalStateException.class,()->CorpusTrainer.train(initial,data,cfg,1));
        var result=CorpusTrainer.train(initial,data,cfg,10_000_000_000L);assertEquals(1,result.state().completedEpochs());
        var tokenizer512=tokenizer;
        Path checkpoint=root.resolve("checkpoint.jade");BrainCheckpoint.saveTraining(checkpoint,result.state(),tokenizer512.model());
        var resumed=BrainCheckpoint.loadTraining(checkpoint,architecture,tokenizer512.model(),data,cfg);
        assertEquals(result.state().optimizer().steps(),resumed.optimizer().steps());
        assertEquals(SgdTrainer.evaluate(result.state().model(),data.validation()),SgdTrainer.evaluate(resumed.model(),data.validation()));
        var wrong=new CorpusTrainer.Data(data.train(),data.validation(),"0".repeat(64),data.utf8Bytes(),data.tokenCount());
        assertThrows(java.io.IOException.class,()->BrainCheckpoint.loadTraining(checkpoint,architecture,tokenizer512.model(),wrong,cfg));
        assertTrue(Double.isFinite(SgdTrainer.evaluate(resumed.model(),StoredDataset.windows(encoded.test(),32,160))));
        for(String prompt:EnglishLearningBenchmark.PROMPTS)
            assertEquals(EnglishLearningBenchmark.generation(resumed.model(),tokenizer512,prompt),EnglishLearningBenchmark.generation(resumed.model(),tokenizer512,prompt));
        assertTrue(encoded.tokens()>0);
    }
}
