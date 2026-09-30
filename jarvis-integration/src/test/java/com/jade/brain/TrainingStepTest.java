package com.jade.brain;
import com.jade.brain.config.BrainConfig;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.objective.LanguageModelObjective;
import com.jade.brain.tokenizer.*;
import com.jade.brain.training.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TrainingStepTest {
 @Test void tinyLearningProof(){var m=new JadeLanguageModel(new BrainConfig(3,4,4,2,1,8),26167);int[] ids={1,2,1,2};var e=TrainingExamples.fromTokens(ids,m.config());var objective=new LanguageModelObjective();double initial=objective.loss(m,ids);var state=TrainingStep.State.initial(m);var c=new TrainingConfig(.01,.9,.999,1e-8,0,1,Set.of());int steps=30;for(int i=0;i<steps;i++){var r=TrainingStep.run(state,e,c);assertEquals(i+1,r.metrics().optimizerStep());assertEquals(3,r.metrics().tokenCount());assertTrue(Double.isFinite(r.metrics().gradientNorm()));state=r.state();}double end=objective.loss(state.model(),ids);assertTrue(end<initial*.5,"Synthetic fixture must learn");assertEquals(initial,objective.loss(m,ids));System.out.println("TINY_LEARNING initial="+initial+" final="+end+" steps="+steps);}
 @Test void bpeUnicodeAndRepeatedTokens(){var tokenizer=new ByteBpeTokenizer(new BpeTrainer().train(List.of("JADE 🤖","hello hello"),270));var c=new BrainConfig(tokenizer.vocabularySize(),32,2,1,1,4);for(String s:List.of("JADE 🤖!","aaaa")){var e=TrainingExamples.fromText(s,tokenizer,c);var ids=tokenizer.encode(s);assertArrayEquals(Arrays.copyOf(ids,ids.length-1),e.inputTokenIds());assertArrayEquals(Arrays.copyOfRange(ids,1,ids.length),e.targetTokenIds());}}
 @Test void contextBoundaryAndInvalidInput(){var c=new BrainConfig(3,3,2,1,1,4);assertEquals(3,TrainingExamples.fromTokens(new int[]{0,1,2,0},c).inputTokenIds().length);assertEquals(2,TrainingExamples.fromTokens(new int[]{0,1,2},c).inputTokenIds().length);assertThrows(IllegalArgumentException.class,()->TrainingExamples.fromTokens(new int[]{0},c));assertThrows(IllegalArgumentException.class,()->TrainingExamples.fromTokens(new int[]{0,1,2,0,1},c));assertThrows(IllegalArgumentException.class,()->TrainingExamples.fromTokens(new int[]{0,3},c));var tokenizer=new ByteBpeTokenizer(new BpeTokenizerModel(BpeVocabulary.base(),List.of()));assertThrows(IllegalArgumentException.class,()->TrainingExamples.fromText("hello",tokenizer,c));}
 @Test void noHiddenGradientAccumulationAndClippingMetrics(){var m=OptimizerTest.model();var e=TrainingExamples.fromTokens(new int[]{1,2,1},m.config());var s=TrainingStep.State.initial(m);var c=new TrainingConfig(.01,.9,.999,1e-8,0,.001,Set.of());var a=TrainingStep.run(s,e,c);var b=TrainingStep.run(s,e,c);assertTrue(a.metrics().clipped());assertEquals(a.metrics(),b.metrics());for(var p:a.state().model().parameters().entrySet())for(int i=0;i<p.getValue().rows();i++)assertArrayEquals(p.getValue().row(i),b.state().model().parameters().get(p.getKey()).row(i));assertEquals(0,s.optimizer().step());}
}
