package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.*;
import com.jade.brain.model.*;
import com.jade.brain.objective.LanguageModelExample;
import com.jade.brain.tokenizer.*;
import com.jade.brain.training.*;
import java.nio.file.*;
import java.util.*;

/** Bounded component timing of the unchanged Java reference, not a replacement trainer. */
public final class JavaProfileHarness {
    static volatile double sink;
    record Result(String name, double minMs, double medianMs, double maxMs) {}
    static Result time(String name, int repetitions, Runnable work) {
        for (int i = 0; i < 2; i++) work.run();
        double[] samples = new double[7];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            for (int j = 0; j < repetitions; j++) work.run();
            samples[i] = (System.nanoTime() - start) / 1e6 / repetitions;
        }
        Arrays.sort(samples);
        return new Result(name, samples[0], samples[3], samples[6]);
    }
    static void print(Result r) {
        System.out.printf(Locale.ROOT, "%s\t%.6f\t%.6f\t%.6f%n", r.name(), r.minMs(), r.medianMs(), r.maxMs());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("corpus-dir checkpoint-output");
        BrainConfig c = new BrainConfig(1024,32,256,4,6,1024);
        JadeLanguageModel model = new JadeLanguageModel(c,26167);
        var w = model.parameters();
        Random random = new Random(55);
        Matrix x=Matrix.random(32,256,random), up=Matrix.random(32,256,random);
        Matrix q=w.get("blocks.0.attention.q.weight"), k=w.get("blocks.0.attention.k.weight"),
                v=w.get("blocks.0.attention.v.weight"), o=w.get("blocks.0.attention.out.weight"),
                u=w.get("blocks.0.ffn.up.weight"), down=w.get("blocks.0.ffn.down.weight");
        Matrix norm=x.rmsNorm(1e-6), qm=norm.multiply(q), km=norm.multiply(k), vm=norm.multiply(v);
        Matrix hidden=norm.multiply(u), gelu=hidden.gelu();
        Matrix logits=x.multiply(w.get("lmHead.weight"));
        int[] ids=new int[33];for(int i=0;i<ids.length;i++)ids[i]=i%32;
        int[] input=Arrays.copyOf(ids,32),target=Arrays.copyOfRange(ids,1,33);
        var example=LanguageModelExample.fromTokens(ids);
        var attention=new CausalSelfAttention(c,q,k,v,o);
        var ffn=new FeedForwardNetwork(u,down);
        TokenEmbedding embedding=new TokenEmbedding(w.get("embedding.weight"));
        var gradient=model.backward(example).gradients();
        System.out.println("component\tmin_ms\tmedian_ms\tmax_ms");
        print(time("embedding_lookup",100,()->sink=embedding.lookup(input).get(0,0)));
        print(time("rmsnorm",100,()->sink=x.rmsNorm(1e-6).get(0,0)));
        print(time("q_projection",10,()->sink=norm.multiply(q).get(0,0)));
        print(time("k_projection",10,()->sink=norm.multiply(k).get(0,0)));
        print(time("v_projection",10,()->sink=norm.multiply(v).get(0,0)));
        print(time("attention_scores",100,()->{
            double sum=0;for(int h=0;h<4;h++)for(int i=0;i<32;i++)for(int j=0;j<=i;j++)
                for(int d=0;d<64;d++)sum+=qm.get(i,h*64+d)*km.get(j,h*64+d)/8;
            sink=sum;
        }));
        double[] scores=new double[32];for(int i=0;i<32;i++)scores[i]=i*.01;
        print(time("softmax_32",1000,()->sink=Matrix.softmax(scores)[0]));
        print(time("attention_value_aggregation",100,()->{
            double sum=0;for(int h=0;h<4;h++)for(int i=0;i<32;i++)for(int d=0;d<64;d++)
                for(int j=0;j<=i;j++)sum+=vm.get(j,h*64+d)*.03125;
            sink=sum;
        }));
        print(time("output_projection",10,()->sink=norm.multiply(o).get(0,0)));
        print(time("ffn_up",3,()->sink=norm.multiply(u).get(0,0)));
        print(time("gelu",100,()->sink=hidden.gelu().get(0,0)));
        print(time("ffn_down",3,()->sink=gelu.multiply(down).get(0,0)));
        print(time("lm_head",3,()->sink=x.multiply(w.get("lmHead.weight")).get(0,0)));
        print(time("cross_entropy",100,()->sink=CrossEntropyLoss.mean(logits.toArray(),target,1024)));
        print(time("backward_attention",3,()->sink=attention.backward(norm,up).dInput().get(0,0)));
        print(time("backward_ffn",3,()->sink=ffn.backward(norm,up).dInput().get(0,0)));
        print(time("backward_embeddings",10,()->sink=embedding.backward(input,up).get(0,0)));
        print(time("parameter_copy",3,()->{
            double sum=0;for(Matrix m:w.values())sum+=m.toArray()[0][0];sink=sum;
        }));
        print(time("gradient_accumulation_two_examples",3,()->{
            double sum=0;for(Matrix m:gradient.values()){
                double[][] a=m.toArray();for(double[] row:a)for(int i=0;i<row.length;i++){row[i]+=row[i];row[i]/=2;}
                sum+=a[0][0];
            }sink=sum;
        }));
        var state=TrainingOptimizer.ADAMW.initial(model);
        var config=new TrainingConfig(.001,.9,.999,1e-8,.01,1,Set.of());
        print(time("adamw_with_clip",2,()->sink=TrainingOptimizer.ADAMW.step(state,gradient,config).model().parameters().get("lmHead.weight").get(0,0)));
        var prepared=ScaleReadinessBenchmark.prepare(Path.of(args[0]));
        var tokenizer=ScaleReadinessBenchmark.fit(prepared,1024);
        var encoded=EnglishCorpus.encode(prepared.manifest(),prepared.split(),ScaleReadinessBenchmark.LIMITS,tokenizer);
        var training=new CorpusTrainer.State(state,ScaleReadinessBenchmark.config(1,2,.001),0,0,1,1,encoded.identity());
        print(time("checkpoint_save_126mb",1,()->{
            try{BrainCheckpoint.saveTraining(Path.of(args[1]),training,tokenizer.model());}
            catch(Exception e){throw new IllegalStateException(e);}
        }));
    }
}
