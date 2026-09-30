package com.jade.brain;
import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.CausalSelfAttention;
import org.junit.jupiter.api.Test;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import static org.junit.jupiter.api.Assertions.*;
class AttentionBackwardTest {
 static final BrainConfig C=new BrainConfig(5,8,4,2,1,6);
 static double dot(Matrix a,Matrix b) { double s=0; for(int i=0;i<a.rows();i++)for(int j=0;j<a.columns();j++)s+=a.get(i,j)*b.get(i,j);return s; }
 static void check(String label,Matrix x,Matrix g,ToDoubleFunction<Matrix> f) {
  double max=0,rel=0;int n=0;
  for(int i=0;i<x.rows();i++)for(int j=0;j<x.columns();j++) {
   double[][] p=x.toArray(),m=x.toArray();p[i][j]+=1e-6;m[i][j]-=1e-6;
   double v=(f.applyAsDouble(new Matrix(p))-f.applyAsDouble(new Matrix(m)))/2e-6;
   double e=Math.abs(v-g.get(i,j));max=Math.max(max,e);rel=Math.max(rel,e/Math.max(1e-12,Math.abs(v)+Math.abs(g.get(i,j))));
   assertEquals(v,g.get(i,j),3e-8,label);n++;
  }
  System.out.println(label+" checked="+n+" maxAbs="+max+" maxRelative="+rel);
 }
 static Matrix x(){return new Matrix(new double[][]{{.7,-.3,1,.2},{-.5,.8,.4,-1},{.2,.1,-.6,.9}});}
 static Matrix g(){return new Matrix(new double[][]{{.3,-.2,.6,.1},{-.1,.5,.2,-.3},{.4,.7,-.2,.5}});}
 static CausalSelfAttention copy(CausalSelfAttention a,int index,Matrix w){return new CausalSelfAttention(C,index==0?w:a.queryWeights(),index==1?w:a.keyWeights(),index==2?w:a.valueWeights(),index==3?w:a.outputWeights());}
 @Test void finiteDifferencesAllProjectionsAndInput(){
  var a=new CausalSelfAttention(C,new Random(26167));var b=a.backward(x(),g());
  check("ATTENTION_X",x(),b.dInput(),v->dot(a.forward(v),g()));
  Matrix[] weights={a.queryWeights(),a.keyWeights(),a.valueWeights(),a.outputWeights()},grads={b.dQuery(),b.dKey(),b.dValue(),b.dOutput()};
  for(int k=0;k<4;k++){final int index=k;check("ATTENTION_W"+k,weights[k],grads[k],v->dot(copy(a,index,v).forward(x()),g()));}
 }
 @Test void causalFutureGradientsExactlyZero(){var a=new CausalSelfAttention(C,new Random(1));var b=a.backward(x(),new Matrix(new double[][]{{1,2,3,4},{0,0,0,0},{0,0,0,0}}));assertArrayEquals(new double[4],b.dInput().row(1));assertArrayEquals(new double[4],b.dInput().row(2));}
 @Test void headSplitAndConcatenationPreserveColumnsInBothDirections(){
  Matrix zero=new Matrix(new double[4][4]);
  Matrix identity=new Matrix(new double[][]{{1,0,0,0},{0,1,0,0},{0,0,1,0},{0,0,0,1}});
  var a=new CausalSelfAttention(C,zero,zero,identity,identity);
  // Each head has deliberately distinct columns. Zero Q/K gives exact uniform causal probabilities.
  Matrix input=new Matrix(new double[][]{{1,2,10,20},{3,4,30,40}});
  Matrix upstream=new Matrix(new double[][]{{2,4,6,8},{10,12,14,16}});
  Matrix joined=new Matrix(new double[][]{{1,2,10,20},{2,3,20,30}});
  Matrix expectedInputGradient=new Matrix(new double[][]{{7,10,13,16},{5,6,7,8}});
  var result=a.backward(input,upstream);
  for(int i=0;i<2;i++){
   assertArrayEquals(joined.row(i),a.forward(input).row(i));
   assertArrayEquals(expectedInputGradient.row(i),result.dInput().row(i));
  }
  Matrix expectedValueGradient=input.transpose().multiply(expectedInputGradient);
  Matrix expectedOutputGradient=joined.transpose().multiply(upstream);
  for(int i=0;i<4;i++){
   assertArrayEquals(new double[4],result.dQuery().row(i));
   assertArrayEquals(new double[4],result.dKey().row(i));
   assertArrayEquals(expectedValueGradient.row(i),result.dValue().row(i));
   assertArrayEquals(expectedOutputGradient.row(i),result.dOutput().row(i));
  }
 }
 @Test void singleTokenHasZeroQueryKeyGradient(){var a=new CausalSelfAttention(C,new Random(2));var b=a.backward(new Matrix(new double[][]{{1,2,3,4}}),new Matrix(new double[][]{{.2,.3,.4,.5}}));for(int i=0;i<4;i++){assertArrayEquals(new double[4],b.dQuery().row(i));assertArrayEquals(new double[4],b.dKey().row(i));}}
 @Test void deterministicAndParametersUnchanged(){var a=new CausalSelfAttention(C,new Random(3));Matrix before=a.forward(x());var b=a.backward(x(),g());var c=a.backward(x(),g());for(int i=0;i<3;i++){assertArrayEquals(before.row(i),a.forward(x()).row(i));assertArrayEquals(b.dInput().row(i),c.dInput().row(i));}assertEquals(4,b.dQuery().rows());assertEquals(4,b.dOutput().columns());}
 @Test void rejectsMalformedAndNonfinite(){var a=new CausalSelfAttention(C,new Random(1));assertThrows(IllegalArgumentException.class,()->a.backward(x(),new Matrix(new double[][]{{1}})));assertThrows(IllegalArgumentException.class,()->a.forward(new Matrix(new double[][]{{1,2}})));assertThrows(IllegalArgumentException.class,()->a.backward(x(),new Matrix(new double[][]{{Double.NaN}})));}
 @Test void largeFiniteScoresRemainFinite(){var a=new CausalSelfAttention(C,new Random(4));Matrix x=new Matrix(new double[][]{{10000,-9000,8000,7000},{-5000,6000,7000,8000}});var b=a.backward(x,new Matrix(new double[][]{{1,2,3,4},{4,3,2,1}}));assertTrue(Double.isFinite(b.dInput().get(0,0)));}
 @Test void excessiveBackwardCacheRejectsBeforeAllocation(){
  var a=new CausalSelfAttention(new BrainConfig(3,2048,2,2,1,4),new Random(1));
  Matrix x=new Matrix(new double[1500][2]);
  var error=assertThrows(IllegalArgumentException.class,()->a.backward(x,x));
  assertEquals("Attention backward cache limit exceeded",error.getMessage());
 }
}
