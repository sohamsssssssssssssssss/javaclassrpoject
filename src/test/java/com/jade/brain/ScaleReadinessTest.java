package com.jade.brain;

import com.jade.brain.config.*;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.*;
import com.jade.brain.training.*;
import com.jade.brain.generation.EnglishOutputMetrics;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScaleReadinessTest {
    @TempDir Path root;
    @Test void exactRegistryAndOverflowSafeMemory() {
        var small=new BrainConfig(512,32,32,4,2,128);
        var model=new JadeLanguageModel(small,42);
        assertEquals(58_368,small.parameterCount());
        assertEquals(small.parameterCount(),model.parameters().values().stream().mapToLong(m->(long)m.rows()*m.columns()).sum());
        var memory=ModelScalePlanner.memory(small,2);
        assertEquals(4*small.parameterCount()*8,memory.persistentState());
        assertEquals(memory.parameters(),memory.gradients());
        assertTrue(memory.estimatedPeak()>memory.persistentState());
        ModelScalePlanner.requireHeap(memory,Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->ModelScalePlanner.requireHeap(memory,100));
        assertThrows(IllegalArgumentException.class,()->BrainConfig.exactParameterCount(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        for(long[] range:new long[][]{{250000,200000,350000},{1000000,800000,1300000},{5000000,4500000,5500000}}){
            var c=ModelScalePlanner.closest(1024,32,range[0],range[1],range[2]);
            assertTrue(c.parameterCount()>=range[1] && c.parameterCount()<=range[2]);
            assertEquals(4*c.embeddingDimension(),c.feedForwardDimension());
            assertEquals(0,c.embeddingDimension()%c.numberOfHeads());
        }
    }
    @Test void diverse1024FitAndFrozenRoundtrip() throws Exception {
        var p=ScaleReadinessBenchmark.prepare(Path.of("src/test/resources/brain/diverse-english"));
        assertTrue(p.manifest().bytes()>=250*1024);
        assertTrue(p.fit().stream().mapToInt(s->s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum()<=65536);
        var tokenizer=ScaleReadinessBenchmark.fit(p,1024);
        assertEquals(1024,tokenizer.vocabularySize());
        for(String sentence:ScaleReadinessBenchmark.SENTENCES)assertEquals(sentence,tokenizer.decode(tokenizer.encode(sentence)));
        assertEquals(12,ScaleReadinessBenchmark.probes(p).size());
    }
    @Test void overlapCatchesCopiesNormalizedSentencesAndNearCopies() throws Exception {
        String passage="The careful reader checked every record before opening the window. A quiet visitor carried a notebook through the garden and watched the birds near the old stone wall.";
        Files.writeString(root.resolve("a.txt"),passage+"\n\n"+passage+" She waited patiently.\n");
        Files.writeString(root.resolve("b.txt"),passage+"\n\nTHE CAREFUL READER CHECKED EVERY RECORD BEFORE OPENING THE WINDOW!\n");
        Files.writeString(root.resolve("c.txt"),passage.replace("quiet visitor","quiet traveler")+"\n");
        var manifest=EnglishCorpus.scan(root,EnglishLearningBenchmark.LIMITS);
        var audit=CorpusOverlapAudit.audit(manifest,EnglishCorpus.split(manifest,.82,.09));
        assertEquals(1,audit.exactLines());assertEquals(1,audit.exactParagraphs());
        assertTrue(audit.normalizedSentences()>=1);assertTrue(audit.similarParagraphs()>=1);
        Path provenance=root.resolve("provenance.tsv");Files.writeString(provenance,"header\n");
        assertThrows(IllegalArgumentException.class,()->CorpusOverlapAudit.writeManifest(manifest,provenance,root.resolve("manifest.tsv")));
    }
    @Test void outputMetricsExposeDegenerateOutput() {
        var metrics=EnglishOutputMetrics.measure("hello hello hello hello\u0001",List.of(1,1,1,1));
        assertTrue(metrics.controlRatio()>0);assertEquals(1,metrics.immediateTokenRepetition());
        assertEquals(.75,metrics.tokenRepetition());
        assertEquals(.5,EnglishOutputMetrics.measure("hello hello hello hello",List.of(1,1,1,1)).repeatedWordTrigrams());
    }
}
