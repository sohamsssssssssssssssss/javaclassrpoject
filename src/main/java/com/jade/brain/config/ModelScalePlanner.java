package com.jade.brain.config;

import java.util.*;

/** Exact architecture search and conservative FP64 payload planning; never allocates a model. */
public final class ModelScalePlanner {
    private ModelScalePlanner() {}
    public record Memory(long parameters, long gradients, long firstMoments, long secondMoments,
                         long persistentState, long updateCopies, long activations, long logits,
                         long attention, long batchStorage, long checkpointPayload, long estimatedPeak) {}

    public static BrainConfig closest(int vocabulary, int context, long target, long minimum, long maximum) {
        if (minimum < 1 || maximum < minimum || target < minimum || target > maximum || maximum > 5_500_000)
            throw new IllegalArgumentException("Invalid bounded parameter target");
        var candidates=new ArrayList<BrainConfig>();
        for(int width:new int[]{64,96,128,160,192,224,256}) for(int layers:new int[]{2,4,6,8}) {
            long count=BrainConfig.exactParameterCount(vocabulary,context,width,layers,4*width);
            if(count>=minimum && count<=maximum) candidates.add(new BrainConfig(vocabulary,context,width,4,layers,4*width));
        }
        return candidates.stream().min(Comparator.comparingLong((BrainConfig c)->Math.abs(c.parameterCount()-target))
                .thenComparingInt(BrainConfig::embeddingDimension).thenComparingInt(BrainConfig::numberOfLayers))
                .orElseThrow(()->new IllegalArgumentException("No conventional architecture in target range"));
    }

    public static Memory memory(BrainConfig c,int batch) {
        if(batch<1 || batch>1024)throw new IllegalArgumentException("Invalid planning batch size");
        long p=Math.multiplyExact(c.parameterCount(),8L);
        long persistent=Math.multiplyExact(p,4); // Parameters, gradients and two moments.
        long updates=Math.multiplyExact(p,7); // Old/new snapshots, copied tensors and batch gradient sums.
        long logits=Math.multiplyExact(Math.multiplyExact((long)c.contextLength(),c.vocabSize()),8);
        long attention=Math.multiplyExact(Math.multiplyExact((long)c.numberOfHeads()*c.contextLength(),c.contextLength()+1L),4);
        long activations=Math.multiplyExact(8L*c.numberOfLayers()*c.contextLength(),
                Math.addExact(12L*c.embeddingDimension(),6L*c.feedForwardDimension()));
        long batchStorage=Math.multiplyExact(12L*c.contextLength(),batch);
        long checkpoint=Math.addExact(Math.multiplyExact(p,3),65536);
        long training=sum(persistent,updates,activations,6*logits,3*attention,batchStorage);
        // ponytail: current checkpoint codec copies full payloads; streaming codec if observed heap demands it.
        long persistence=sum(persistent,Math.multiplyExact(checkpoint,4));
        long peak=Math.addExact(Math.max(training,persistence),64L*1024*1024); // JVM/row-object margin, not a heap measurement.
        return new Memory(p,p,p,p,persistent,updates,activations,logits,attention,batchStorage,checkpoint,peak);
    }
    private static long sum(long... values){long result=0;for(long value:values)result=Math.addExact(result,value);return result;}
    public static void requireHeap(Memory memory,long maximumHeap) {
        if(maximumHeap<1 || memory.estimatedPeak()>maximumHeap-maximumHeap/4)
            throw new IllegalArgumentException("Estimated peak exceeds 75% heap safety budget");
    }
}
