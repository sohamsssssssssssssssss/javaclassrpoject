package com.jade.brain;
import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.*;
import com.jade.brain.objective.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModelBackwardTest {
 @Test void endToEndEveryParameterAcrossTwoLayers(){
  var c=new BrainConfig(4,4,2,1,2,3);var m=new JadeLanguageModel(c,26167);int[] ids={1,2,1,3};var ex=LanguageModelExample.fromTokens(ids);var r=m.backward(ex);
  assertEquals(m.parameters().keySet(),r.gradients().keySet());assertTrue(Double.isFinite(r.loss()));
  var objective=new LanguageModelObjective();
  for(var entry:m.parameters().entrySet()) {
   String name=entry.getKey();AttentionBackwardTest.check("MODEL_"+name,entry.getValue(),r.gradients().get(name),v->{var p=new LinkedHashMap<>(m.parameters());p.put(name,v);return objective.loss(new JadeLanguageModel(c,p),ids);});
  }
 }
 @Test void singleLayerDeterminismRegistryAndNonmutation(){var m=new JadeLanguageModel(new BrainConfig(5,4,4,2,1,6),17);int[] ids={1,2,1};var before=m.forward(new int[]{1,2});var a=m.backward(LanguageModelExample.fromTokens(ids));var b=m.backward(LanguageModelExample.fromTokens(ids));assertEquals(9,m.parameters().size());assertEquals(new ArrayList<>(m.parameters().keySet()),new ArrayList<>(a.gradients().keySet()));for(var e:a.gradients().entrySet())for(int i=0;i<e.getValue().rows();i++)assertArrayEquals(e.getValue().row(i),b.gradients().get(e.getKey()).row(i));for(int i=0;i<2;i++)assertArrayEquals(before[i],m.forward(new int[]{1,2})[i]);assertNotSame(m.parameters().get("embedding.weight"),m.parameters().get("lmHead.weight"));}
 @Test void embeddingRepeatedIdsAccumulateAndUnusedRowsZero(){var e=new TokenEmbedding(new Matrix(new double[4][2]));var g=e.backward(new int[]{1,2,1},new Matrix(new double[][]{{1,2},{3,4},{5,6}}));assertArrayEquals(new double[]{6,8},g.row(1));assertArrayEquals(new double[]{3,4},g.row(2));assertArrayEquals(new double[]{0,0},g.row(0));assertArrayEquals(new double[]{0,0},g.row(3));assertThrows(IllegalArgumentException.class,()->e.backward(new int[]{4},new Matrix(new double[][]{{1,2}})));assertThrows(IllegalArgumentException.class,()->e.backward(new int[]{1},new Matrix(new double[][]{{1}})));}
 @Test void invalidExamplesAndParametersReject(){var m=new JadeLanguageModel(new BrainConfig(4,2,2,1,1,3),1);assertThrows(IllegalArgumentException.class,()->m.backward(new LanguageModelExample(new int[]{1,2,3},new int[]{2,3,1})));assertThrows(IllegalArgumentException.class,()->m.backward(new LanguageModelExample(new int[]{1},new int[]{4})));var p=new LinkedHashMap<>(m.parameters());p.put("extra",new Matrix(new double[][]{{1}}));assertThrows(IllegalArgumentException.class,()->new JadeLanguageModel(m.config(),p));p.remove("extra");p.put("lmHead.weight",new Matrix(new double[][]{{1}}));assertThrows(IllegalArgumentException.class,()->new JadeLanguageModel(m.config(),p));}
}
