package com.jade.brain.training;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import java.util.*;
public final class Gradients {
 private Gradients(){}
 public static void validate(JadeLanguageModel model,Map<String,Matrix> gradients){
  var p=model.parameters();if(!p.keySet().equals(gradients.keySet()))throw new IllegalArgumentException("Gradient identities differ");
  for(var e:p.entrySet()){Matrix g=Objects.requireNonNull(gradients.get(e.getKey()));if(g.rows()!=e.getValue().rows()||g.columns()!=e.getValue().columns())throw new IllegalArgumentException("Gradient shape differs: "+e.getKey());}
 }
 public static double norm(Map<String,Matrix> gradients){
  double n=0;for(Matrix g:gradients.values())for(int i=0;i<g.rows();i++)for(int j=0;j<g.columns();j++)n=Math.hypot(n,g.get(i,j));
  if(!Double.isFinite(n))throw new IllegalArgumentException("Gradient norm exceeds finite range");return n;
 }
 public record Clipped(Map<String,Matrix> gradients,double originalNorm,boolean clipped){
  public Clipped {gradients=Collections.unmodifiableMap(new LinkedHashMap<>(gradients));}
 }
 public static Clipped clip(Map<String,Matrix> gradients,double threshold){
  if(!Double.isFinite(threshold)||threshold<0)throw new IllegalArgumentException("Invalid clipping threshold");
  double n=norm(gradients);boolean clipped=threshold>0&&n>threshold;
  if(!clipped)return new Clipped(gradients,n,false);
  double scale=threshold/n;var result=new LinkedHashMap<String,Matrix>();
  for(var e:gradients.entrySet()){double[][] a=e.getValue().toArray();for(double[] row:a)for(int j=0;j<row.length;j++)row[j]*=scale;result.put(e.getKey(),new Matrix(a));}
  return new Clipped(result,n,true);
 }
}
