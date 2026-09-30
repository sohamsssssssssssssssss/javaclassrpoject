package com.jade.brain;
import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.objective.LanguageModelExample;
import com.jade.brain.training.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OptimizerTest {
 static JadeLanguageModel model(){return new JadeLanguageModel(new BrainConfig(3,4,2,1,1,3),26167);}
 static TrainingConfig config(double decay,Set<String> exclude){return new TrainingConfig(.01,.9,.999,1e-8,decay,0,exclude);}
 static Map<String,Matrix> uniform(JadeLanguageModel m,double value){var g=new LinkedHashMap<String,Matrix>();for(var e:m.parameters().entrySet()){double[][] a=e.getValue().toArray();for(double[] r:a)Arrays.fill(r,value);g.put(e.getKey(),new Matrix(a));}return g;}
 @Test void globalNormAndClipping(){var g=Map.of("a",new Matrix(new double[][]{{3}}),"b",new Matrix(new double[][]{{4}}));assertEquals(5,Gradients.norm(g));var r=Gradients.clip(g,2);assertTrue(r.clipped());assertEquals(2,Gradients.norm(r.gradients()),1e-15);assertEquals(3,g.get("a").get(0,0));assertFalse(Gradients.clip(g,6).clipped());assertFalse(Gradients.clip(Map.of("z",new Matrix(new double[][]{{0}})),1).clipped());assertThrows(IllegalArgumentException.class,()->Gradients.clip(g,Double.NaN));}
 @Test void firstAndSecondUpdatesMatchManualBiasCorrection(){var m=model();var state=AdamW.State.initial(m);var g=uniform(m,.2);var c=config(0,Set.of());var a=AdamW.step(m,g,state,c);var b=AdamW.step(a.model(),g,a.optimizer(),c);for(var e:m.parameters().entrySet()){var n=e.getKey();double old=e.getValue().get(0,0);double expectedStep=.01*.2/(.2+1e-8);assertEquals(old-expectedStep,a.model().parameters().get(n).get(0,0),1e-14);assertEquals(old-2*expectedStep,b.model().parameters().get(n).get(0,0),1e-14);assertEquals(.02,a.optimizer().moments().get(n).first().get(0,0),1e-14);assertEquals(.00004,a.optimizer().moments().get(n).second().get(0,0),1e-14);}assertEquals(2,b.optimizer().step());assertEquals(0,state.step());}
 @Test void zeroGradientWeightDecayAndNoDecay(){var m=model();var a=AdamW.step(m,uniform(m,0),AdamW.State.initial(m),config(.1,Set.of("embedding.weight")));for(var e:m.parameters().entrySet()){double w=e.getValue().get(0,0);assertEquals(w*(e.getKey().equals("embedding.weight")?1:.999),a.model().parameters().get(e.getKey()).get(0,0),1e-15);}}
 @Test void invalidUpdateNeverPartiallyChangesModelOrState(){var m=model();var s=AdamW.State.initial(m);var before=m.parameters();var g=uniform(m,.1);g.remove("lmHead.weight");var missing=g;assertThrows(IllegalArgumentException.class,()->AdamW.step(m,missing,s,config(0,Set.of())));g=uniform(m,.1);g.put("lmHead.weight",new Matrix(new double[][]{{1}}));var malformed=g;assertThrows(IllegalArgumentException.class,()->AdamW.step(m,malformed,s,config(0,Set.of())));for(var e:before.entrySet())assertSame(e.getValue(),m.parameters().get(e.getKey()));assertEquals(0,s.step());}
 @Test void overflowRejectsWithoutCommit(){var m=model();var s=AdamW.State.initial(m);assertThrows(IllegalArgumentException.class,()->AdamW.step(m,uniform(m,Double.MAX_VALUE),s,config(0,Set.of())));assertEquals(0,s.step());}
 @Test void deterministicRealLossGradientUpdate(){var m=model();var r=m.backward(LanguageModelExample.fromTokens(new int[]{1,2,1}));var a=AdamW.step(m,r.gradients(),AdamW.State.initial(m),config(0,Set.of()));var b=AdamW.step(m,r.gradients(),AdamW.State.initial(m),config(0,Set.of()));for(var e:a.model().parameters().entrySet())for(int i=0;i<e.getValue().rows();i++)assertArrayEquals(e.getValue().row(i),b.model().parameters().get(e.getKey()).row(i));assertNotEquals(m.parameters().get("lmHead.weight").get(0,0),a.model().parameters().get("lmHead.weight").get(0,0));}
 @Test void registryParameterCountIsExact(){var m=model();long count=0;for(var w:m.parameters().values())count+=(long)w.rows()*w.columns();assertEquals(m.parameterCount(),count);assertEquals(9,m.parameters().size());}
 @Test void normAvoidsSquaredOverflowForLargeFiniteGradients(){
  var g=Map.of("large",new Matrix(new double[][]{{1e200,1e200}}));
  assertEquals(Math.sqrt(2)*1e200,Gradients.norm(g),1e185);
  assertEquals(1,Gradients.norm(Gradients.clip(g,1).gradients()),1e-15);
 }
 @Test void invalidConfigAndStateReject(){assertThrows(IllegalArgumentException.class,()->config(-1,Set.of()));var m=model();assertThrows(IllegalArgumentException.class,()->AdamW.step(m,uniform(m,0),AdamW.State.initial(m),config(0,Set.of("bogus"))));assertThrows(IllegalArgumentException.class,()->new AdamW.Moments(new Matrix(new double[][]{{0}}),new Matrix(new double[][]{{-1}})));assertThrows(IllegalArgumentException.class,()->AdamW.validateState(m,new AdamW.State(0,Map.of())));}
}
