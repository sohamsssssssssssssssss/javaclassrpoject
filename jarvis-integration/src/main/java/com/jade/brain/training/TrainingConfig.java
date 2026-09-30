package com.jade.brain.training;
import java.util.Set;
import java.util.Objects;
/** Explicit fixture/future-run settings; no production training defaults. Clip=0 disables clipping. */
public record TrainingConfig(double learningRate,double beta1,double beta2,double epsilon,
                             double weightDecay,double clipThreshold,Set<String> noDecayParameters) {
 public TrainingConfig {
  for(double v:new double[]{learningRate,beta1,beta2,epsilon,weightDecay,clipThreshold})
   if(!Double.isFinite(v))throw new IllegalArgumentException("Non-finite optimizer configuration");
  if(learningRate<=0||beta1<0||beta1>=1||beta2<0||beta2>=1||epsilon<=0||weightDecay<0||clipThreshold<0)
   throw new IllegalArgumentException("Invalid optimizer configuration");
  noDecayParameters=Set.copyOf(Objects.requireNonNull(noDecayParameters));
  if(noDecayParameters.stream().anyMatch(String::isBlank))throw new IllegalArgumentException("Blank parameter identity");
 }
}
