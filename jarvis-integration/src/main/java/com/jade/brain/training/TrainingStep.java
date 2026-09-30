package com.jade.brain.training;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.objective.LanguageModelExample;
import java.util.Objects;
/** One example, one fresh mean gradient, one explicit transactional update. No background training. */
public final class TrainingStep {
 private TrainingStep(){}
 public record State(JadeLanguageModel model,AdamW.State optimizer){
  public State {Objects.requireNonNull(model);Objects.requireNonNull(optimizer);AdamW.validateState(model,optimizer);}
  public static State initial(JadeLanguageModel model){return new State(model,AdamW.State.initial(model));}
 }
 public record Metrics(double loss,int tokenCount,double gradientNorm,boolean clipped,long optimizerStep){}
 public record Result(State state,Metrics metrics){}
 public static Result run(State state,LanguageModelExample example,TrainingConfig config){
  TrainingExamples.validate(example,state.model().config());
  var r=state.model().backward(example);Gradients.validate(state.model(),r.gradients());
  var clipped=Gradients.clip(r.gradients(),config.clipThreshold());
  var update=AdamW.step(state.model(),clipped.gradients(),state.optimizer(),config);
  return new Result(new State(update.model(),update.optimizer()),new Metrics(r.loss(),example.targetTokenIds().length,clipped.originalNorm(),clipped.clipped(),update.optimizer().step()));
 }
}
