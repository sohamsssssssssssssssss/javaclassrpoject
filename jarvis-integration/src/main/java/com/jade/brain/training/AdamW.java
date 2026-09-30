package com.jade.brain.training;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import java.util.*;
/** Immutable optimizer state and transactional replacement snapshots, never in-place model writes. */
public final class AdamW {
 private AdamW(){}
 public record Moments(Matrix first,Matrix second){
  public Moments {Objects.requireNonNull(first);Objects.requireNonNull(second);
   if(first.rows()!=second.rows()||first.columns()!=second.columns())throw new IllegalArgumentException("Moment shapes differ");
   for(int i=0;i<second.rows();i++)for(int j=0;j<second.columns();j++)if(second.get(i,j)<0)throw new IllegalArgumentException("Negative second moment");}
 }
 public record State(long step,Map<String,Moments> moments){
  public State {if(step<0)throw new IllegalArgumentException("Negative optimizer step");moments=Collections.unmodifiableMap(new LinkedHashMap<>(moments));for(var m:moments.values())Objects.requireNonNull(m);}
  public static State initial(JadeLanguageModel model){var m=new LinkedHashMap<String,Moments>();for(var e:model.parameters().entrySet()){var w=e.getValue();var z=new Matrix(new double[w.rows()][w.columns()]);m.put(e.getKey(),new Moments(z,z));}return new State(0,m);}
 }
 public record Update(JadeLanguageModel model,State optimizer){ }
 public static void validateState(JadeLanguageModel model,State state){
  if(!model.parameters().keySet().equals(state.moments().keySet()))throw new IllegalArgumentException("Optimizer identities differ");
  for(var e:model.parameters().entrySet()){var m=state.moments().get(e.getKey());if(m.first().rows()!=e.getValue().rows()||m.first().columns()!=e.getValue().columns())throw new IllegalArgumentException("Optimizer shape differs");}
 }
 public static Update step(JadeLanguageModel model,Map<String,Matrix> gradients,State state,TrainingConfig c){
  Gradients.validate(model,gradients);validateState(model,state);
  if(!model.parameters().keySet().containsAll(c.noDecayParameters()))throw new IllegalArgumentException("Unknown no-decay identity");
  long t=Math.addExact(state.step(),1);double b1=1-Math.pow(c.beta1(),t),b2=1-Math.pow(c.beta2(),t);
  var parameters=new LinkedHashMap<String,Matrix>();var moments=new LinkedHashMap<String,Moments>();
  for(var e:model.parameters().entrySet()){
   String n=e.getKey();Matrix w=e.getValue(),g=gradients.get(n);var previous=state.moments().get(n);
   double[][] p=w.toArray(),m=previous.first().toArray(),v=previous.second().toArray();
   for(int i=0;i<w.rows();i++)for(int j=0;j<w.columns();j++){
    double x=g.get(i,j);m[i][j]=c.beta1()*m[i][j]+(1-c.beta1())*x;v[i][j]=c.beta2()*v[i][j]+(1-c.beta2())*x*x;
    p[i][j]=w.get(i,j)-c.learningRate()*(m[i][j]/b1)/(Math.sqrt(v[i][j]/b2)+c.epsilon());
    if(!c.noDecayParameters().contains(n))p[i][j]-=c.learningRate()*c.weightDecay()*w.get(i,j);
   }
   parameters.put(n,new Matrix(p));moments.put(n,new Moments(new Matrix(m),new Matrix(v)));
  }
  // All computations/shape/finite checks finish before a replacement is returned.
  return new Update(new JadeLanguageModel(model.config(),parameters),new State(t,moments));
 }
}
