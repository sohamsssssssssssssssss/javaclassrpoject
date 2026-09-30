package com.jade.brain;
import com.jade.brain.config.*;
import com.jade.brain.model.*;
import com.jade.brain.training.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Random Java initialization and frozen Java token windows, never pretrained weights. */
public final class BenchmarkFixtures {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
        var p=ScaleReadinessBenchmark.prepare(root);
        for(int vocabulary:new int[]{512,1024}){
            var tokenizer=ScaleReadinessBenchmark.fit(p,vocabulary);
            var e=EnglishCorpus.encode(p.manifest(),p.split(),ScaleReadinessBenchmark.LIMITS,tokenizer);
            var train=e.dataset(32,32).train();var text=new StringBuilder();
            for(int index:new int[]{train.size()/3,2*train.size()/3}){
                var example=train.get(index).example();
                for(int id:example.inputTokenIds())text.append(id).append(' ');
                text.append(example.targetTokenIds()[31]).append('\n');
            }
            Files.writeString(out.resolve("tokens-"+vocabulary+".txt"),text);
            System.out.println("vocab="+vocabulary+" corpus="+p.manifest().digest()+" encoding="+e.identity());
        }
        var configs=List.of(new BrainConfig(512,32,32,4,2,128),new BrainConfig(1024,32,64,4,2,256),
                new BrainConfig(1024,32,128,4,4,512),new BrainConfig(1024,32,256,4,6,1024));
        String[] names={"58K","250K","1M","5M"};
        for(int i=0;i<configs.size();i++){
            var model=new JadeLanguageModel(configs.get(i),26167);
            try(var file=new BufferedOutputStream(Files.newOutputStream(out.resolve("weights-"+names[i]+".bin")))){
                var buffer=ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
                for(var matrix:model.parameters().values())for(int r=0;r<matrix.rows();r++)for(int c=0;c<matrix.columns();c++){
                    if(buffer.remaining()<4){file.write(buffer.array(),0,buffer.position());buffer.clear();}
                    buffer.putFloat((float)matrix.get(r,c));
                }
                file.write(buffer.array(),0,buffer.position());
            }
            System.out.println("model="+names[i]+" seed=26167 parameters="+model.parameterCount());
        }
    }
}
