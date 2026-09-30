package com.jade.brain;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.*;
import com.jade.brain.model.*;
import com.jade.brain.objective.LanguageModelExample;
import com.jade.brain.training.*;
import java.util.*;

/** Deterministic FP64 oracle data. C++ converts these values to FP32 on load. */
public final class ParityFixture {
    static void emit(String kind,String name,double[] values) {
        System.out.print(kind+" "+name+" "+values.length);
        for(double value:values)System.out.print(" "+Double.toString(value));
        System.out.println();
    }
    static double[] flat(Matrix matrix){
        double[] values=new double[matrix.rows()*matrix.columns()];int cursor=0;
        for(double[] row:matrix.toArray())for(double value:row)values[cursor++]=value;
        return values;
    }
    static Matrix weight(Map<String,Matrix> weights,String key){return weights.get("blocks.0."+key+".weight");}
    public static void main(String[] ignored){
        var config=new BrainConfig(32,4,8,2,1,16);
        var model=new JadeLanguageModel(config,26167);
        var weights=model.parameters();int[] input={1,2,3,4},target={2,3,4,5};
        System.out.println("CONFIG 32 4 8 2 1 16");
        System.out.println("INPUT 1 2 3 4");System.out.println("TARGET 2 3 4 5");
        for(var entry:weights.entrySet())emit("PARAM",entry.getKey(),flat(entry.getValue()));
        Matrix token=new TokenEmbedding(weights.get("embedding.weight")).lookup(input);
        Matrix position=new TokenEmbedding(weights.get("position.weight")).lookup(new int[]{0,1,2,3});
        Matrix embedding=token.add(position),norm1=embedding.rmsNorm(1e-6);
        Matrix q=norm1.multiply(weight(weights,"attention.q")),k=norm1.multiply(weight(weights,"attention.k")),
                v=norm1.multiply(weight(weights,"attention.v"));
        double[][] joined=new double[4][8],scores=new double[8][4],probabilities=new double[8][4];
        for(double[] row:scores)Arrays.fill(row,-1e9);
        for(int h=0;h<2;h++)for(int i=0;i<4;i++){
            double[] prefix=new double[i+1];
            for(int j=0;j<=i;j++){
                for(int c=0;c<4;c++)prefix[j]+=q.get(i,h*4+c)*k.get(j,h*4+c);
                prefix[j]/=2;scores[h*4+i][j]=prefix[j];
            }
            double[] p=Matrix.softmax(prefix);
            for(int j=0;j<=i;j++)probabilities[h*4+i][j]=p[j];
            for(int c=0;c<4;c++)for(int j=0;j<=i;j++)joined[i][h*4+c]+=p[j]*v.get(j,h*4+c);
        }
        Matrix joinedMatrix=new Matrix(joined),projected=joinedMatrix.multiply(weight(weights,"attention.out"));
        Matrix residual1=embedding.add(projected),norm2=residual1.rmsNorm(1e-6);
        Matrix pre=norm2.multiply(weight(weights,"ffn.up")),gelu=pre.gelu(),ffnOut=gelu.multiply(weight(weights,"ffn.down"));
        Matrix residual2=residual1.add(ffnOut),finalNorm=residual2.rmsNorm(1e-6),logits=finalNorm.multiply(weights.get("lmHead.weight"));
        for(var item:List.of(
                Map.entry("embedding",embedding),Map.entry("norm1",norm1),Map.entry("q",q),Map.entry("k",k),
                Map.entry("v",v),Map.entry("masked_scores",new Matrix(scores)),
                Map.entry("probabilities",new Matrix(probabilities)),Map.entry("joined",joinedMatrix),
                Map.entry("att_projection",projected),Map.entry("residual1",residual1),Map.entry("norm2",norm2),
                Map.entry("ffn_pre",pre),Map.entry("gelu",gelu),Map.entry("ffn_out",ffnOut),
                Map.entry("residual2",residual2),Map.entry("final_norm",finalNorm),Map.entry("logits",logits)))
            emit("TRACE",item.getKey(),flat(item.getValue()));
        var backward=model.backward(LanguageModelExample.fromTokens(new int[]{1,2,3,4,5}));
        System.out.println("LOSS "+Double.toString(backward.loss()));
        for(var entry:backward.gradients().entrySet())emit("GRAD",entry.getKey(),flat(entry.getValue()));
        var optimizer=TrainingOptimizer.ADAMW.initial(model);
        var settings=new TrainingConfig(.001,.9,.999,1e-8,.01,1,Set.of());
        for(int step=0;step<3;step++)optimizer=TrainingOptimizer.ADAMW.step(optimizer,backward.gradients(),settings);
        for(var entry:optimizer.model().parameters().entrySet()){
            String name=entry.getKey();emit("ADAM_PARAM",name,flat(entry.getValue()));
            var moment=optimizer.adamw().moments().get(name);
            emit("ADAM_M",name,flat(moment.first()));emit("ADAM_V",name,flat(moment.second()));
        }
    }
}
