package com.jade.brain.model;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.math.LinearBackward;
import java.util.Random;
import java.util.Objects;

/** Bias-free scaled dot-product multi-head attention with an exact causal mask. */
public final class CausalSelfAttention {
    private final BrainConfig config;
    private final Matrix query, key, value, output;

    public CausalSelfAttention(BrainConfig config, Random random) {
        this(config, Matrix.random(config.embeddingDimension(), config.embeddingDimension(), random),
                Matrix.random(config.embeddingDimension(), config.embeddingDimension(), random),
                Matrix.random(config.embeddingDimension(), config.embeddingDimension(), random),
                Matrix.random(config.embeddingDimension(), config.embeddingDimension(), random));
    }
    public CausalSelfAttention(BrainConfig config, Matrix query, Matrix key, Matrix value, Matrix output) {
        this.config = Objects.requireNonNull(config);
        for (Matrix w : new Matrix[]{query, key, value, output}) {
            if (w.rows() != config.embeddingDimension() || w.columns() != config.embeddingDimension())
                throw new IllegalArgumentException("Invalid attention weights");
        }
        this.query=query; this.key=key; this.value=value; this.output=output;
    }
    public Matrix queryWeights() { return query; }
    public Matrix keyWeights() { return key; }
    public Matrix valueWeights() { return value; }
    public Matrix outputWeights() { return output; }
    private record Cache(Matrix input, Matrix q, Matrix k, Matrix v, Matrix joined, double[][][] probabilities) { }
    private Cache cache(Matrix input, boolean retainProbabilities) {
        if (input.columns() != config.embeddingDimension() || input.rows() > config.contextLength())
            throw new IllegalArgumentException("Invalid attention input shape");
        Matrix q=input.multiply(query), k=input.multiply(key), v=input.multiply(value);
        int d=config.embeddingDimension()/config.numberOfHeads(), t=input.rows();
        double[][] joined=new double[t][input.columns()];
        // ponytail: CPU backward cache capped at two million probabilities; chunking can lift this later.
        if(retainProbabilities && (long)config.numberOfHeads()*t*(t+1)/2 > 2_000_000)
            throw new IllegalArgumentException("Attention backward cache limit exceeded");
        double[][][] probabilities=retainProbabilities ? new double[config.numberOfHeads()][t][] : null;
        for(int h=0;h<config.numberOfHeads();h++) {
            int offset=h*d;
            for(int i=0;i<t;i++) {
                double[] scores=new double[i+1];
                for(int j=0;j<=i;j++) {
                    for(int c=0;c<d;c++) scores[j]+=q.get(i,offset+c)*k.get(j,offset+c);
                    scores[j]/=Math.sqrt(d);
                }
                double[] p=Matrix.softmax(scores); if(retainProbabilities) probabilities[h][i]=p;
                for(int c=0;c<d;c++) for(int j=0;j<=i;j++) joined[i][offset+c]+=p[j]*v.get(j,offset+c);
            }
        }
        return new Cache(input,q,k,v,new Matrix(joined),probabilities);
    }
    public Matrix forward(Matrix input) { return cache(input, false).joined.multiply(output); }
    public record BackwardResult(Matrix dInput, Matrix dQuery, Matrix dKey, Matrix dValue, Matrix dOutput) {
        public BackwardResult { Objects.requireNonNull(dInput); Objects.requireNonNull(dQuery);
            Objects.requireNonNull(dKey); Objects.requireNonNull(dValue); Objects.requireNonNull(dOutput); }
    }
    public BackwardResult backward(Matrix input, Matrix upstream) {
        Cache a=cache(input, true);
        LinearBackward.Result out=LinearBackward.calculate(a.joined,output,upstream);
        int t=input.rows(), dim=input.columns(), d=dim/config.numberOfHeads();
        double[][] dq=new double[t][dim], dk=new double[t][dim], dv=new double[t][dim];
        for(int h=0;h<config.numberOfHeads();h++) {
            int offset=h*d;
            for(int i=0;i<t;i++) {
                double[] p=a.probabilities[h][i], dp=new double[i+1];
                for(int j=0;j<=i;j++) for(int c=0;c<d;c++) {
                    dp[j]+=out.dInput().get(i,offset+c)*a.v.get(j,offset+c);
                    dv[j][offset+c]+=p[j]*out.dInput().get(i,offset+c);
                }
                double dot=0; for(int j=0;j<=i;j++) dot+=dp[j]*p[j];
                // Future columns do not exist in these rows; their score gradient is exactly zero.
                for(int j=0;j<=i;j++) {
                    double ds=p[j]*(dp[j]-dot)/Math.sqrt(d);
                    for(int c=0;c<d;c++) {
                        dq[i][offset+c]+=ds*a.k.get(j,offset+c);
                        dk[j][offset+c]+=ds*a.q.get(i,offset+c);
                    }
                }
            }
        }
        LinearBackward.Result q=LinearBackward.calculate(input,query,new Matrix(dq));
        LinearBackward.Result k=LinearBackward.calculate(input,key,new Matrix(dk));
        LinearBackward.Result v=LinearBackward.calculate(input,value,new Matrix(dv));
        return new BackwardResult(q.dInput().add(k.dInput()).add(v.dInput()),q.dWeights(),k.dWeights(),v.dWeights(),out.dWeights());
    }
}
