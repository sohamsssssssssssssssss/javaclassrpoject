package com.jade.brain;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.*;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
class BlockBackwardTest {
 @Test void completeBlockFiniteDifferences(){
  var b=new TransformerBlock(AttentionBackwardTest.C,new Random(26167));var x=AttentionBackwardTest.x();var g=AttentionBackwardTest.g();var r=b.backward(x,g);
  AttentionBackwardTest.check("BLOCK_X",x,r.dInput(),v->AttentionBackwardTest.dot(b.forward(v),g));
  var a=b.attention();Matrix[] w={a.queryWeights(),a.keyWeights(),a.valueWeights(),a.outputWeights()};Matrix[] d={r.attention().dQuery(),r.attention().dKey(),r.attention().dValue(),r.attention().dOutput()};
  for(int k=0;k<4;k++){int index=k;AttentionBackwardTest.check("BLOCK_ATTENTION_"+k,w[k],d[k],v->AttentionBackwardTest.dot(new TransformerBlock(AttentionBackwardTest.copy(a,index,v),b.ffn()).forward(x),g));}
  AttentionBackwardTest.check("BLOCK_FFN_UP",b.ffn().upWeights(),r.dUp(),v->AttentionBackwardTest.dot(new TransformerBlock(a,new FeedForwardNetwork(v,b.ffn().downWeights())).forward(x),g));
  AttentionBackwardTest.check("BLOCK_FFN_DOWN",b.ffn().downWeights(),r.dDown(),v->AttentionBackwardTest.dot(new TransformerBlock(a,new FeedForwardNetwork(b.ffn().upWeights(),v)).forward(x),g));
 }
 @Test void deterministicImmutableAndForwardInvariant(){
  var b=new TransformerBlock(AttentionBackwardTest.C,new Random(2));
  var x=AttentionBackwardTest.x();var g=AttentionBackwardTest.g();var before=b.forward(x);
  var p=b.backward(x,g);var q=b.backward(x,g);
  for(int i=0;i<x.rows();i++){
   assertArrayEquals(before.row(i),b.forward(x).row(i));
   assertArrayEquals(p.dInput().row(i),q.dInput().row(i));
  }
  Matrix[] first={p.attention().dQuery(),p.attention().dKey(),p.attention().dValue(),
    p.attention().dOutput(),p.dUp(),p.dDown()};
  Matrix[] second={q.attention().dQuery(),q.attention().dKey(),q.attention().dValue(),
    q.attention().dOutput(),q.dUp(),q.dDown()};
  for(int i=0;i<first.length;i++)for(int row=0;row<first[i].rows();row++)
   assertArrayEquals(first[i].row(row),second[i].row(row));
  assertThrows(IllegalArgumentException.class,()->b.backward(x,new Matrix(new double[][]{{1}})));
 }
 @Test void zeroBranchesLeaveIdentityResidualGradient(){Matrix z=new Matrix(new double[4][4]);var a=new CausalSelfAttention(AttentionBackwardTest.C,z,z,z,z);var b=new TransformerBlock(a,new FeedForwardNetwork(new Matrix(new double[4][6]),new Matrix(new double[6][4])));var r=b.backward(AttentionBackwardTest.x(),AttentionBackwardTest.g());for(int i=0;i<3;i++)assertArrayEquals(AttentionBackwardTest.g().row(i),r.dInput().row(i));}
 @Test void bothTransformedResidualBranchesContribute(){
  var b=new TransformerBlock(AttentionBackwardTest.C,new Random(26167));
  Matrix x=AttentionBackwardTest.x(),g=AttentionBackwardTest.g(),residual=b.afterAttention(x);
  Matrix ffnPath=residual.rmsNormBackward(b.ffn().backward(residual.rmsNorm(1e-6),g).dInput(),1e-6);
  Matrix dResidual=g.add(ffnPath);
  Matrix attentionPath=x.rmsNormBackward(b.attention().backward(x.rmsNorm(1e-6),dResidual).dInput(),1e-6);
  assertTrue(Math.abs(ffnPath.get(0,0))>1e-10);
  assertTrue(Math.abs(attentionPath.get(0,0))>1e-10);
  Matrix expected=dResidual.add(attentionPath),actual=b.backward(x,g).dInput();
  for(int i=0;i<x.rows();i++)assertArrayEquals(expected.row(i),actual.row(i));
 }
 @Test void backwardLeavesEveryBlockParameterExactlyUnchanged(){
  var b=new TransformerBlock(AttentionBackwardTest.C,new Random(26167));
  Matrix[] weights={b.attention().queryWeights(),b.attention().keyWeights(),
    b.attention().valueWeights(),b.attention().outputWeights(),
    b.ffn().upWeights(),b.ffn().downWeights()};
  double[][][] original=new double[weights.length][][];
  for(int i=0;i<weights.length;i++)original[i]=weights[i].toArray();
  b.backward(AttentionBackwardTest.x(),AttentionBackwardTest.g());
  for(int i=0;i<weights.length;i++)for(int row=0;row<weights[i].rows();row++)
    assertArrayEquals(original[i][row],weights[i].row(row));
 }
}
