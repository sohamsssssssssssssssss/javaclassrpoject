package com.jade.brain.model;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import java.util.Objects;
import java.util.Random;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import com.jade.brain.math.CrossEntropyLoss;
import com.jade.brain.objective.LanguageModelExample;

/** Experimental pure-Java model with immutable parameters and explicit manual backward. */
public final class JadeLanguageModel {
    private final BrainConfig config;
    private final TokenEmbedding tokens, positions;
    private final TransformerBlock[] blocks;
    private final TransformerStack stack;
    private final LmHead languageHead;

    public JadeLanguageModel(BrainConfig config, long seed) {
        this.config = Objects.requireNonNull(config, "config");
        Random random = new Random(seed);
        tokens = new TokenEmbedding(config.vocabSize(), config.embeddingDimension(), random);
        positions = new TokenEmbedding(config.contextLength(), config.embeddingDimension(), random);
        blocks = new TransformerBlock[config.numberOfLayers()];
        for (int i = 0; i < blocks.length; i++) blocks[i] = new TransformerBlock(config, random);
        stack = new TransformerStack(java.util.Arrays.asList(blocks));
        languageHead = new LmHead(Matrix.random(config.embeddingDimension(), config.vocabSize(), random));
    }

    public long parameterCount() { return config.parameterCount(); }
    /** Reconstruct an immutable snapshot; reject missing, extra or mis-shaped parameters. */
    public JadeLanguageModel(BrainConfig config, Map<String, Matrix> parameters) {
        this.config = Objects.requireNonNull(config);
        int d=config.embeddingDimension(), f=config.feedForwardDimension();
        tokens=new TokenEmbedding(require(parameters,"embedding.weight",config.vocabSize(),d));
        positions=new TokenEmbedding(require(parameters,"position.weight",config.contextLength(),d));
        blocks=new TransformerBlock[config.numberOfLayers()];
        for(int i=0;i<blocks.length;i++) {
            String p="blocks."+i+".";
            blocks[i]=new TransformerBlock(new CausalSelfAttention(config,
                require(parameters,p+"attention.q.weight",d,d),require(parameters,p+"attention.k.weight",d,d),
                require(parameters,p+"attention.v.weight",d,d),require(parameters,p+"attention.out.weight",d,d)),
                new FeedForwardNetwork(require(parameters,p+"ffn.up.weight",d,f),require(parameters,p+"ffn.down.weight",f,d)));
        }
        stack=new TransformerStack(java.util.Arrays.asList(blocks));
        languageHead=new LmHead(require(parameters,"lmHead.weight",d,config.vocabSize()));
        if(!parameters.keySet().equals(parameters().keySet())) throw new IllegalArgumentException("Unexpected parameter names");
    }
    private static Matrix require(Map<String,Matrix> p,String name,int rows,int columns) {
        Matrix w=Objects.requireNonNull(p.get(name),"Missing parameter "+name);
        if(w.rows()!=rows || w.columns()!=columns) throw new IllegalArgumentException("Invalid shape for "+name);
        return w;
    }
    /** Stable registry: immutable matrices, untied embeddings/head, unit-scale norms. */
    public Map<String,Matrix> parameters() {
        var p=new LinkedHashMap<String,Matrix>();
        p.put("embedding.weight",tokens.weights());p.put("position.weight",positions.weights());
        for(int i=0;i<blocks.length;i++) {
            String n="blocks."+i+".";var a=blocks[i].attention();
            p.put(n+"attention.q.weight",a.queryWeights());p.put(n+"attention.k.weight",a.keyWeights());
            p.put(n+"attention.v.weight",a.valueWeights());p.put(n+"attention.out.weight",a.outputWeights());
            p.put(n+"ffn.up.weight",blocks[i].ffn().upWeights());p.put(n+"ffn.down.weight",blocks[i].ffn().downWeights());
        }
        p.put("lmHead.weight",languageHead.weights());return Collections.unmodifiableMap(p);
    }
    public record BackwardResult(double loss, int supervisedTokenCount, Matrix logits,
                                 Map<String,Matrix> gradients,
                                 TransformerStack.BackwardResult stackGradients,
                                 Matrix dEmbeddedInput) {
        public BackwardResult {
            if(!Double.isFinite(loss) || loss<0 || supervisedTokenCount<1)
                throw new IllegalArgumentException("Invalid loss or supervised-token count");
            Objects.requireNonNull(logits);
            if(logits.rows()!=supervisedTokenCount) throw new IllegalArgumentException("Logit position count differs");
            gradients=Collections.unmodifiableMap(new LinkedHashMap<>(gradients));
            Objects.requireNonNull(stackGradients);
            Objects.requireNonNull(dEmbeddedInput);
        }
        public Matrix dTokenEmbedding() { return gradients.get("embedding.weight"); }
        public Matrix dPositionEmbedding() { return gradients.get("position.weight"); }
        public Matrix dHeadWeights() { return gradients.get("lmHead.weight"); }
    }
    /** Complete token sequence includes one final target token with no input position. */
    public BackwardResult backwardTokens(int[] completeTokens) {
        return backward(LanguageModelExample.fromTokens(completeTokens));
    }
    /** One immutable output-path forward pass, including the exact stack caches used by backward. */
    public static final class OutputForward {
        private final JadeLanguageModel owner;
        private final TransformerStack.ForwardPass stackPass;
        private final Matrix hidden, logits;
        private final int[] targets;
        private OutputForward(JadeLanguageModel owner, TransformerStack.ForwardPass stackPass,
                              Matrix hidden, Matrix logits, int[] targets) {
            this.owner = owner;
            this.stackPass = stackPass;
            this.hidden = hidden;
            this.logits = logits;
            this.targets = targets.clone();
        }
        public Matrix logits() { return logits; }
        public int supervisedTokenCount() { return targets.length; }
        public int[] targets() { return targets.clone(); }
    }
    /** Stops at stack input: token and position embedding gradients belong to the next boundary. */
    public record OutputBackwardResult(double meanLoss, int supervisedTokenCount, Matrix logits,
                                       Matrix dLogits, Matrix dHeadWeights, Matrix dStackOutput,
                                       TransformerStack.BackwardResult stackGradients) {
        public OutputBackwardResult {
            if (!Double.isFinite(meanLoss) || meanLoss < 0 || supervisedTokenCount < 1)
                throw new IllegalArgumentException("Invalid output-path loss");
            Objects.requireNonNull(logits); Objects.requireNonNull(dLogits);
            Objects.requireNonNull(dHeadWeights); Objects.requireNonNull(dStackOutput);
            Objects.requireNonNull(stackGradients);
        }
        public double totalLoss() {
            double total = meanLoss * supervisedTokenCount;
            if (!Double.isFinite(total)) throw new IllegalArgumentException("Total loss exceeds finite range");
            return total;
        }
        public Matrix dStackInput() { return stackGradients.dInput(); }
    }
    public OutputForward forwardOutput(LanguageModelExample example) {
        Objects.requireNonNull(example, "example");
        int[] ids=example.inputTokenIds(), targets=example.targetTokenIds();
        if (ids.length > config.contextLength()) throw new IllegalArgumentException("Context exceeded");
        for (int target : targets) if (target >= config.vocabSize())
            throw new IllegalArgumentException("Invalid target token ID");
        int[] pos=new int[ids.length];for(int i=0;i<pos.length;i++)pos[i]=i;
        var pass=stack.forwardCached(tokens.lookup(ids).add(positions.lookup(pos)));
        Matrix hidden=pass.output().rmsNorm(TransformerBlock.EPSILON);
        return new OutputForward(this,pass,hidden,languageHead.forward(hidden),targets);
    }
    public OutputBackwardResult backwardOutput(OutputForward forward) {
        if (forward == null || forward.owner != this)
            throw new IllegalArgumentException("Invalid output-path forward cache");
        var ce=CrossEntropyLoss.meanWithGradient(forward.logits.toArray(),forward.targets,config.vocabSize());
        var head=languageHead.backward(forward.hidden,ce.dLogits());
        Matrix dStackOutput=forward.stackPass.output().rmsNormBackward(head.dHidden(),TransformerBlock.EPSILON);
        var stackGradients=stack.backward(forward.stackPass,dStackOutput);
        return new OutputBackwardResult(ce.loss(),forward.targets.length,forward.logits,
                ce.dLogits(),head.dWeights(),dStackOutput,stackGradients);
    }
    public OutputBackwardResult backwardOutput(LanguageModelExample example) {
        return backwardOutput(forwardOutput(example));
    }
    public BackwardResult backward(LanguageModelExample example) {
        int[] ids=example.inputTokenIds();
        int[] pos=new int[ids.length];for(int i=0;i<pos.length;i++)pos[i]=i;
        var output=backwardOutput(example);
        var gradients=new LinkedHashMap<String,Matrix>();gradients.put("lmHead.weight",output.dHeadWeights());
        var stackGradients=output.stackGradients();
        for(int i=blocks.length-1;i>=0;i--) {
            var r=stackGradients.layers().get(i);String n="blocks."+i+".";
            gradients.put(n+"attention.q.weight",r.attention().dQuery());gradients.put(n+"attention.k.weight",r.attention().dKey());
            gradients.put(n+"attention.v.weight",r.attention().dValue());gradients.put(n+"attention.out.weight",r.attention().dOutput());
            gradients.put(n+"ffn.up.weight",r.dUp());gradients.put(n+"ffn.down.weight",r.dDown());
        }
        Matrix g=stackGradients.dInput();
        gradients.put("embedding.weight",tokens.backward(ids,g));gradients.put("position.weight",positions.backward(pos,g));
        var ordered=new LinkedHashMap<String,Matrix>();for(String n:parameters().keySet())ordered.put(n,gradients.get(n));
        return new BackwardResult(output.meanLoss(),output.supervisedTokenCount(),output.logits(),
                ordered,stackGradients,g);
    }
    public BrainConfig config() { return config; }
    public LmHead lmHead() { return languageHead; }

    /** The normal forward pass's last position predicts the next token. */
    public double[] nextTokenLogits(int[] context) {
        double[][] logits = forward(context);
        return logits[logits.length - 1];
    }

    public double[][] forward(int[] tokenIds) {
        return languageHead.forward(outputHidden(tokenIds)).toArray();
    }

    /** Post-final-RMSNorm states used by the LM head. */
    public Matrix outputHidden(int[] tokenIds) {
        return transformerOutput(tokenIds).rmsNorm(TransformerBlock.EPSILON);
    }

    /** Explicit final-normalization backward; returns the final block output gradient only. */
    public Matrix backwardFinalNorm(int[] tokenIds, Matrix dFinalHidden) {
        return transformerOutput(tokenIds).rmsNormBackward(dFinalHidden, TransformerBlock.EPSILON);
    }

    public Matrix transformerOutput(int[] tokenIds) {
        Objects.requireNonNull(tokenIds, "tokenIds");
        if (tokenIds.length == 0 || tokenIds.length > config.contextLength()) {
            throw new IllegalArgumentException("Sequence must fit the nonempty context window");
        }
        int[] positionIds = new int[tokenIds.length];
        for (int i = 0; i < positionIds.length; i++) positionIds[i] = i;
        Matrix hidden = tokens.lookup(tokenIds).add(positions.lookup(positionIds));
        return stack.forward(hidden);
    }
}
