package com.jade.brain;
import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.*;
import com.jade.brain.training.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class CheckpointTest {
 @TempDir Path directory;
 static void same(Matrix a,Matrix b){assertEquals(a.rows(),b.rows());assertEquals(a.columns(),b.columns());for(int i=0;i<a.rows();i++)assertArrayEquals(a.row(i),b.row(i));}
 static void sameState(TrainingStep.State a,TrainingStep.State b){assertEquals(a.model().config(),b.model().config());assertEquals(a.optimizer().step(),b.optimizer().step());assertEquals(a.model().parameters().keySet(),b.model().parameters().keySet());for(String n:a.model().parameters().keySet()){same(a.model().parameters().get(n),b.model().parameters().get(n));same(a.optimizer().moments().get(n).first(),b.optimizer().moments().get(n).first());same(a.optimizer().moments().get(n).second(),b.optimizer().moments().get(n).second());}}
 BrainCheckpoint.Snapshot fixture(){var tok=new BpeTrainer().train(List.of("hello jade","hello brain"),264);var model=new JadeLanguageModel(new BrainConfig(tok.vocabularySize(),16,2,1,1,4),26167);var training=new TrainingConfig(.005,.9,.999,1e-8,.01,1,Set.of("embedding.weight"));var e=TrainingExamples.fromText("hello jade!",new ByteBpeTokenizer(tok),model.config());var s=TrainingStep.run(TrainingStep.State.initial(model),e,training).state();return new BrainCheckpoint.Snapshot(s,tok,training,26167);}
 @Test void exactRoundTripAndNextStepReplay()throws Exception{
  var s=fixture();Path p=directory.resolve("tiny.jade"),q=directory.resolve("again.jade");BrainCheckpoint.save(p,s);var loaded=BrainCheckpoint.load(p);sameState(s.state(),loaded.state());assertEquals(s.training(),loaded.training());assertEquals(s.initializationSeed(),loaded.initializationSeed());assertEquals(s.tokenizer().vocabulary().tokens(),loaded.tokenizer().vocabulary().tokens());assertEquals(s.tokenizer().merges(),loaded.tokenizer().merges());BrainCheckpoint.save(q,loaded);assertArrayEquals(Files.readAllBytes(p),Files.readAllBytes(q));
  var e=TrainingExamples.fromText("hello jade!",new ByteBpeTokenizer(s.tokenizer()),s.state().model().config());var a=TrainingStep.run(s.state(),e,s.training());var b=TrainingStep.run(loaded.state(),e,loaded.training());assertEquals(a.metrics(),b.metrics());sameState(a.state(),b.state());int[] ids=e.inputTokenIds();for(int i=0;i<ids.length;i++)assertArrayEquals(a.state().model().forward(ids)[i],b.state().model().forward(ids)[i]);System.out.println("CHECKPOINT exactParameters=true exactMoments=true exactTokenizer=true nextForward=true nextUpdate=true bytes="+Files.size(p));
 }
 static void resign(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes,bytes.length-32));System.arraycopy(hash,0,bytes,bytes.length-32,32);}
 @Test void corruptionAndTruncationReject()throws Exception{Path p=directory.resolve("broken.jade");BrainCheckpoint.save(p,fixture());byte[] b=Files.readAllBytes(p);b[100]^=1;Files.write(p,b);assertThrows(IOException.class,()->BrainCheckpoint.load(p));Files.write(p,Arrays.copyOf(b,20));assertThrows(IOException.class,()->BrainCheckpoint.load(p));}
 @Test void unsupportedVersionAndIncompatibleShapesRejectEvenWithValidChecksum()throws Exception{Path p=directory.resolve("wrong.jade");BrainCheckpoint.save(p,fixture());byte[] original=Files.readAllBytes(p);byte[] b=original.clone();ByteBuffer.wrap(b).putInt(4,999);resign(b);Files.write(p,b);assertThrows(IOException.class,()->BrainCheckpoint.load(p));b=original.clone();ByteBuffer.wrap(b).putInt(16,3);resign(b);Files.write(p,b);assertThrows(IOException.class,()->BrainCheckpoint.load(p));}
 @Test void mismatchedTokenizerRejected(){var s=fixture();var base=new BpeTokenizerModel(BpeVocabulary.base(),List.of());assertThrows(IllegalArgumentException.class,()->new BrainCheckpoint.Snapshot(s.state(),base,s.training(),1));}
 @Test void excessiveSizeRejectsBeforeAllocation()throws Exception{Path p=directory.resolve("oversized.jade");try(var f=new RandomAccessFile(p.toFile(),"rw")){f.setLength(64L*1024*1024+1);}assertThrows(IOException.class,()->BrainCheckpoint.load(p));}
 @Test void invalidNumericPayloadRejectedWithValidChecksum()throws Exception{
  Path p=directory.resolve("nan.jade");BrainCheckpoint.save(p,fixture());byte[] b=Files.readAllBytes(p);
  var raw=new ByteArrayInputStream(b);var in=new DataInputStream(raw);in.skipNBytes(88);int excluded=in.readInt();for(int i=0;i<excluded;i++)in.readUTF();int v=in.readInt();for(int i=0;i<v;i++){int n=in.readInt();in.skipNBytes(n);}int merges=in.readInt();in.skipNBytes(16L*merges);in.readInt();in.readUTF();in.readInt();in.readInt();int offset=b.length-raw.available();ByteBuffer.wrap(b).putDouble(offset,Double.NaN);resign(b);Files.write(p,b);assertThrows(IOException.class,()->BrainCheckpoint.load(p));
 }
}
