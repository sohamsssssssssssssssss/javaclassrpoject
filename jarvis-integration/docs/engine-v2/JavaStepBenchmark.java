package com.jade.brain;
import com.jade.brain.config.*;
import com.jade.brain.model.*;
import com.jade.brain.training.*;
import java.nio.file.*;
import java.util.*;

/** Matched warmed baseline: same two windows, seeds, warmups and seven timed samples as C++. */
public final class JavaStepBenchmark {
    static void stats(String name,double[] values){
        var sorted=values.clone();Arrays.sort(sorted);
        System.out.printf(Locale.ROOT,"%s_min=%.9f %s_median=%.9f %s_max=%.9f ",name,sorted[0],name,sorted[3],name,sorted[6]);
    }
    public static void main(String[] args)throws Exception{
        String name=args[0];var c=switch(name){
            case "58K"->new BrainConfig(512,32,32,4,2,128);
            case "250K"->new BrainConfig(1024,32,64,4,2,256);
            case "1M"->new BrainConfig(1024,32,128,4,4,512);
            case "5M"->new BrainConfig(1024,32,256,4,6,1024);
            default->throw new IllegalArgumentException("Unknown model");};
        var p=ScaleReadinessBenchmark.prepare(Path.of(args[1]));var tokenizer=ScaleReadinessBenchmark.fit(p,c.vocabSize());
        var e=EnglishCorpus.encode(p.manifest(),p.split(),ScaleReadinessBenchmark.LIMITS,tokenizer);
        var train=e.dataset(32,32).train();var batch=new TokenDataset.Batch(List.of(train.get(train.size()/3),train.get(2*train.size()/3)));
        var settings=ScaleReadinessBenchmark.config(1,2,.001).optimizerConfig();
        var state=TrainingOptimizer.ADAMW.initial(new JadeLanguageModel(c,26167));
        for(int i=0;i<2;i++)state=TrainingOptimizer.ADAMW.step(state,SgdTrainer.batchGradient(state.model(),batch).gradients(),settings);
        double[] f=new double[7],b=new double[7],a=new double[7],total=new double[7],rate=new double[7];long heap=0;
        for(int i=0;i<7;i++){
            long start=System.nanoTime();SgdTrainer.batchLoss(state.model(),batch);f[i]=(System.nanoTime()-start)/1e9;
            start=System.nanoTime();var gradient=SgdTrainer.batchGradient(state.model(),batch);b[i]=(System.nanoTime()-start)/1e9;
            start=System.nanoTime();state=TrainingOptimizer.ADAMW.step(state,gradient.gradients(),settings);a[i]=(System.nanoTime()-start)/1e9;
            total[i]=b[i]+a[i];rate[i]=64/total[i];heap=Math.max(heap,ScaleReadinessBenchmark.heap());
        }
        System.out.print("JAVA model="+name+" parameters="+c.parameterCount()+" context=32 batch=2 warmup=2 samples=7 ");
        stats("forward",f);stats("backwardIncludingForward",b);stats("adamw",a);stats("step",total);stats("positionsPerSecond",rate);
        System.out.println("observedUsedHeap="+heap+" maxHeap="+Runtime.getRuntime().maxMemory());
    }
}
