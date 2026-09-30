package com.jade.brain.training;
import com.jade.brain.config.BrainConfig;
import com.jade.brain.tokenizer.BrainTokenizer;
import com.jade.brain.objective.LanguageModelExample;
public final class TrainingExamples {
 private TrainingExamples(){}
 public static LanguageModelExample fromTokens(int[] tokens,BrainConfig config){
  var e=LanguageModelExample.fromTokens(tokens);validate(e,config);return e;
 }
 public static LanguageModelExample fromText(String text,BrainTokenizer tokenizer,BrainConfig config){
  if(tokenizer.vocabularySize()!=config.vocabSize())throw new IllegalArgumentException("Tokenizer/model vocabulary mismatch");
  return fromTokens(tokenizer.encode(text),config);
 }
 public static void validate(LanguageModelExample e,BrainConfig config){
  if(e.inputTokenIds().length>config.contextLength())throw new IllegalArgumentException("Shifted input exceeds context; no truncation/padding");
  for(int[] ids:new int[][]{e.inputTokenIds(),e.targetTokenIds()})for(int id:ids)if(id<0||id>=config.vocabSize())throw new IllegalArgumentException("Invalid training token ID");
 }
}
