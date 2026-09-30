package com.jade.brain.training;

import com.jade.brain.config.BrainConfig;
import com.jade.brain.math.Matrix;
import com.jade.brain.model.JadeLanguageModel;
import com.jade.brain.tokenizer.*;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.*;
import java.util.*;

/** JADE-owned deterministic binary format v1, SHA-256 corruption check, bounded strict reader. */
public final class BrainCheckpoint {
 private static final int MAGIC=0x4a414445,VERSION=1,MAX_BYTES=160*1024*1024;
 private BrainCheckpoint(){}
 public record Snapshot(TrainingStep.State state,BpeTokenizerModel tokenizer,TrainingConfig training,long initializationSeed){
  public Snapshot {
   Objects.requireNonNull(state);Objects.requireNonNull(tokenizer);Objects.requireNonNull(training);
   if(state.model().config().vocabSize()!=tokenizer.vocabularySize())throw new IllegalArgumentException("Checkpoint tokenizer/model vocabulary mismatch");
   if(!state.model().parameters().keySet().containsAll(training.noDecayParameters()))throw new IllegalArgumentException("Unknown no-decay parameter");
  }
 }
 private static byte[] digest(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static void matrix(DataOutputStream out,Matrix m)throws IOException{
  out.writeInt(m.rows());out.writeInt(m.columns());for(int i=0;i<m.rows();i++)for(int j=0;j<m.columns();j++)out.writeDouble(m.get(i,j));
 }
 private static Matrix matrix(DataInputStream in,Matrix shape)throws IOException{
  int r=in.readInt(),c=in.readInt();if(r!=shape.rows()||c!=shape.columns())throw new IOException("Checkpoint matrix shape mismatch");
  if(8L*r*c>in.available())throw new EOFException("Truncated checkpoint matrix");
  double[][] a=new double[r][c];for(int i=0;i<r;i++)for(int j=0;j<c;j++)a[i][j]=in.readDouble();return new Matrix(a);
 }
 private static int count(DataInputStream in,int min,int max)throws IOException{int n=in.readInt();if(n<min||n>max)throw new IOException("Invalid checkpoint count");return n;}
 public static void save(Path path,Snapshot snapshot)throws IOException{
  checkSaveSize(snapshot.state().model().parameterCount(),true);
  var bytes=new ByteArrayOutputStream();
  try(var out=new DataOutputStream(bytes)){
   out.writeInt(MAGIC);out.writeInt(VERSION);var c=snapshot.state().model().config();
   for(int v:new int[]{c.vocabSize(),c.contextLength(),c.embeddingDimension(),c.numberOfHeads(),c.numberOfLayers(),c.feedForwardDimension()})out.writeInt(v);
   out.writeLong(snapshot.initializationSeed());var t=snapshot.training();
   for(double v:new double[]{t.learningRate(),t.beta1(),t.beta2(),t.epsilon(),t.weightDecay(),t.clipThreshold()})out.writeDouble(v);
   var excluded=t.noDecayParameters().stream().sorted().toList();out.writeInt(excluded.size());for(String n:excluded)out.writeUTF(n);
   var tok=snapshot.tokenizer();out.writeInt(tok.vocabularySize());
   for(var token:tok.vocabulary().tokens()){byte[] b=token.bytes();out.writeInt(b.length);out.write(b);}
   out.writeInt(tok.mergeCount());for(var m:tok.merges()){out.writeInt(m.leftTokenId());out.writeInt(m.rightTokenId());out.writeInt(m.resultTokenId());out.writeInt(m.rank());}
   var parameters=snapshot.state().model().parameters();out.writeInt(parameters.size());
   for(var e:parameters.entrySet()){out.writeUTF(e.getKey());matrix(out,e.getValue());}
   out.writeLong(snapshot.state().optimizer().step());
   for(String n:parameters.keySet()){var m=snapshot.state().optimizer().moments().get(n);matrix(out,m.first());matrix(out,m.second());}
  }
  byte[] body=bytes.toByteArray();if(body.length+32>MAX_BYTES)throw new IOException("Checkpoint size limit exceeded");
  writeAtomic(path,body);
 }
 public static Snapshot load(Path path)throws IOException{
  byte[] body=readVerified(path);
  try(var in=new DataInputStream(new ByteArrayInputStream(body))){
   if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported checkpoint format/version");
   var c=new BrainConfig(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());long seed=in.readLong();
   double lr=in.readDouble(),b1=in.readDouble(),b2=in.readDouble(),eps=in.readDouble(),decay=in.readDouble(),clip=in.readDouble();
   int exclusions=count(in,0,3+6*c.numberOfLayers());var noDecay=new HashSet<String>();for(int i=0;i<exclusions;i++)if(!noDecay.add(in.readUTF()))throw new IOException("Duplicate no-decay identity");
   var training=new TrainingConfig(lr,b1,b2,eps,decay,clip,noDecay);
   int vocab=count(in,256,BpeVocabulary.MAX_VOCABULARY_SIZE);if(vocab!=c.vocabSize())throw new IOException("Tokenizer/model vocabulary mismatch");
   var tokens=new ArrayList<BpeToken>();int tokenBytes=0;
   for(int i=0;i<vocab;i++){int n=count(in,1,BpeVocabulary.MAX_TOKEN_BYTES);tokenBytes+=n;if(tokenBytes>BpeVocabulary.MAX_VOCABULARY_BYTES)throw new IOException("Vocabulary byte limit exceeded");byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();tokens.add(new BpeToken(i,b));}
   int mergeCount=count(in,0,BpeMerge.MAX_MERGES);var merges=new ArrayList<BpeMerge>();for(int i=0;i<mergeCount;i++)merges.add(new BpeMerge(in.readInt(),in.readInt(),in.readInt(),in.readInt()));
   var tokenizer=new BpeTokenizerModel(new BpeVocabulary(tokens),merges);
   var shapes=new JadeLanguageModel(c,seed).parameters();
   if(count(in,shapes.size(),shapes.size())!=shapes.size())throw new IOException("Parameter count mismatch");
   var parameters=new LinkedHashMap<String,Matrix>();for(var e:shapes.entrySet()){if(!in.readUTF().equals(e.getKey()))throw new IOException("Parameter identity/order mismatch");parameters.put(e.getKey(),matrix(in,e.getValue()));}
   long step=in.readLong();var moments=new LinkedHashMap<String,AdamW.Moments>();for(var e:shapes.entrySet())moments.put(e.getKey(),new AdamW.Moments(matrix(in,e.getValue()),matrix(in,e.getValue())));
   if(in.available()!=0)throw new IOException("Unexpected checkpoint trailing data");
   return new Snapshot(new TrainingStep.State(new JadeLanguageModel(c,parameters),new AdamW.State(step,moments)),tokenizer,training,seed);
  }catch(IllegalArgumentException|NullPointerException e){throw new IOException("Invalid checkpoint state",e);}
 }

 public static final int TRAINING_VERSION=2;

 /** Version 2 stores a full epoch boundary, optimizer selection and deterministic identities. */
 public static void saveTraining(Path path,CorpusTrainer.State state,BpeTokenizerModel tokenizer)throws IOException{
  if(state.model().config().vocabSize()!=tokenizer.vocabularySize())throw new IOException("Tokenizer/model mismatch");
  checkSaveSize(state.model().parameterCount(),state.config().optimizer()==TrainingOptimizer.ADAMW);
  var bytes=new ByteArrayOutputStream();
  try(var out=new DataOutputStream(bytes)){
   out.writeInt(MAGIC);out.writeInt(TRAINING_VERSION);var architecture=state.model().config();
   for(int v:new int[]{architecture.vocabSize(),architecture.contextLength(),architecture.embeddingDimension(),architecture.numberOfHeads(),architecture.numberOfLayers(),architecture.feedForwardDimension()})out.writeInt(v);
   var config=state.config();out.writeUTF(config.optimizer().name());var d=config.data();
   for(int v:new int[]{d.contextLength(),d.stride(),d.batchSize(),d.epochs()})out.writeInt(v);
   out.writeDouble(d.learningRate());out.writeDouble(d.trainFraction());out.writeInt(d.maxInputBytes());out.writeInt(d.maxTokens());
   var t=config.optimizerConfig();for(double v:new double[]{t.learningRate(),t.beta1(),t.beta2(),t.epsilon(),t.weightDecay(),t.clipThreshold()})out.writeDouble(v);
   var exclusions=t.noDecayParameters().stream().sorted().toList();out.writeInt(exclusions.size());for(String name:exclusions)out.writeUTF(name);
   out.writeBoolean(config.shuffleTrainingExamples());out.writeLong(config.shuffleSeed());
   out.writeInt(state.completedEpochs());out.writeLong(state.optimizer().steps());out.writeLong(state.totalSupervisedTokens());
   out.writeDouble(state.initialTrainingLoss());out.writeDouble(state.initialValidationLoss());out.writeUTF(state.corpusIdentity());
   out.write(tokenizerIdentity(tokenizer));
   var parameters=state.model().parameters();out.writeInt(parameters.size());
   for(var e:parameters.entrySet()){out.writeUTF(e.getKey());matrix(out,e.getValue());}
   if(config.optimizer()==TrainingOptimizer.ADAMW)for(String name:parameters.keySet()){
    var m=state.optimizer().adamw().moments().get(name);matrix(out,m.first());matrix(out,m.second());
   }
  }
  writeAtomic(path,bytes.toByteArray());
 }

 /** Compatible objects are supplied by the caller; nothing live is mutated, even on failure. */
 public static CorpusTrainer.State loadTraining(Path path,BrainConfig expectedArchitecture,
       BpeTokenizerModel tokenizer,TextCorpus corpus,CorpusTrainer.Config expectedConfig)throws IOException{
  return loadTraining(path,expectedArchitecture,tokenizer,
      CorpusTrainer.prepare(new JadeLanguageModel(expectedArchitecture,0),corpus,expectedConfig),expectedConfig);
 }

 /** Same v2 format and validation; lazy token-store callers need no flat corpus array. */
 public static CorpusTrainer.State loadTraining(Path path,BrainConfig expectedArchitecture,
       BpeTokenizerModel tokenizer,CorpusTrainer.Data corpus,CorpusTrainer.Config expectedConfig)throws IOException{
  byte[] body=readVerified(path);
  try(var in=new DataInputStream(new ByteArrayInputStream(body))){
   if(in.readInt()!=MAGIC||in.readInt()!=TRAINING_VERSION)throw new IOException("Unsupported training checkpoint magic/version");
   var architecture=new BrainConfig(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());
   if(!architecture.equals(expectedArchitecture))throw new IOException("Incompatible checkpoint architecture");
   var type=TrainingOptimizer.valueOf(in.readUTF());
   var data=new SgdTrainer.Config(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readDouble(),in.readDouble(),in.readInt(),in.readInt());
   double lr=in.readDouble(),b1=in.readDouble(),b2=in.readDouble(),eps=in.readDouble(),decay=in.readDouble(),clip=in.readDouble();
   int excluded=count(in,0,3+6*architecture.numberOfLayers());var exclusions=new LinkedHashSet<String>();
   for(int i=0;i<excluded;i++)if(!exclusions.add(in.readUTF()))throw new IOException("Duplicate exclusion");
   var optimizerConfig=new TrainingConfig(lr,b1,b2,eps,decay,clip,exclusions);
   var savedConfig=new CorpusTrainer.Config(data,type,optimizerConfig,in.readBoolean(),in.readLong());
   if(!savedConfig.compatible(expectedConfig))throw new IOException("Incompatible checkpoint training configuration/optimizer");
   int completed=in.readInt();long steps=in.readLong(),supervised=in.readLong();
   double initialTrain=in.readDouble(),initialValidation=in.readDouble();String corpusId=in.readUTF();
   if(completed<0||completed>data.epochs()||completed>expectedConfig.data().epochs()
       ||!corpusId.equals(corpus.identity()))throw new IOException("Incompatible checkpoint corpus/epoch");
   if(tokenizer.vocabularySize()!=architecture.vocabSize()
       ||!MessageDigest.isEqual(in.readNBytes(32),tokenizerIdentity(tokenizer)))throw new IOException("Incompatible checkpoint tokenizer");
   // Architecture is bounded and matched before generating any shape arrays.
   var shapes=new JadeLanguageModel(architecture,0).parameters();
   count(in,shapes.size(),shapes.size());var parameters=new LinkedHashMap<String,Matrix>();
   for(var e:shapes.entrySet()){
    if(!in.readUTF().equals(e.getKey()))throw new IOException("Parameter identity/order mismatch");
    parameters.put(e.getKey(),matrix(in,e.getValue()));
   }
   AdamW.State adamw=null;
   if(type==TrainingOptimizer.ADAMW){
    var moments=new LinkedHashMap<String,AdamW.Moments>();
    for(var e:shapes.entrySet())moments.put(e.getKey(),new AdamW.Moments(matrix(in,e.getValue()),matrix(in,e.getValue())));
    adamw=new AdamW.State(steps,moments);
   }
   if(in.available()!=0)throw new IOException("Unexpected checkpoint trailing data");
   var state=new CorpusTrainer.State(new TrainingOptimizer.State(new JadeLanguageModel(architecture,parameters),type,steps,adamw),
       expectedConfig,completed,supervised,initialTrain,initialValidation,corpusId);
   CorpusTrainer.validateResume(state,corpus,expectedConfig);
   return state;
  }catch(IllegalArgumentException|NullPointerException|ArithmeticException e){throw new IOException("Invalid training checkpoint state",e);}
 }

 private static byte[] tokenizerIdentity(BpeTokenizerModel tokenizer)throws IOException{
  var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
   out.writeInt(tokenizer.vocabularySize());
   for(var token:tokenizer.vocabulary().tokens()){byte[] b=token.bytes();out.writeInt(b.length);out.write(b);}
   out.writeInt(tokenizer.mergeCount());for(var merge:tokenizer.merges()){
    out.writeInt(merge.leftTokenId());out.writeInt(merge.rightTokenId());out.writeInt(merge.resultTokenId());out.writeInt(merge.rank());
   }
  }return digest(bytes.toByteArray());
 }

 private static byte[] readVerified(Path path)throws IOException{
  if(Files.size(path)>MAX_BYTES)throw new IOException("Checkpoint size limit exceeded");
  byte[] file;try(var input=Files.newInputStream(path)){file=input.readNBytes(MAX_BYTES+1);}
  if(file.length>MAX_BYTES||file.length<40)throw new IOException("Invalid checkpoint length");
  byte[] body=Arrays.copyOf(file,file.length-32),hash=Arrays.copyOfRange(file,file.length-32,file.length);
  if(!MessageDigest.isEqual(hash,digest(body)))throw new IOException("Checkpoint checksum mismatch");
  return body;
 }

 private static void checkSaveSize(long parameters,boolean adamw)throws IOException{
  // Includes a conservative bounded allowance for tokenizer/configuration/tensor headers.
  if(Math.addExact(Math.multiplyExact(parameters,adamw?24L:8L),4L*1024*1024)>MAX_BYTES)
   throw new IOException("Checkpoint size limit exceeded before serialization");
 }

 private static void writeAtomic(Path path,byte[] body)throws IOException{
  if(body.length+32L>MAX_BYTES)throw new IOException("Checkpoint size limit exceeded");
  Path target=path.toAbsolutePath(),temp=Files.createTempFile(target.getParent(),".jade-checkpoint-",".tmp");
  try{
   try(var out=Files.newOutputStream(temp)){out.write(body);out.write(digest(body));out.flush();}
   try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
   try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
   catch(AtomicMoveNotSupportedException unsupported){replaceWithBackup(temp,target);}
  }finally{Files.deleteIfExists(temp);}
 }

 /** Non-atomic filesystems: keep a backup until replacement succeeds; retain it if recovery fails. */
 static void replaceWithBackup(Path temp,Path target)throws IOException{
  Path backup=null;
  if(Files.exists(target)){
   backup=Files.createTempFile(target.getParent(),".jade-checkpoint-backup-",".tmp");
   Files.copy(target,backup,StandardCopyOption.REPLACE_EXISTING);
  }
  try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
  catch(IOException failed){
   if(backup!=null)try{Files.copy(backup,target,StandardCopyOption.REPLACE_EXISTING);}
     catch(IOException recovery){failed.addSuppressed(new IOException("Previous checkpoint retained at "+backup,recovery));throw failed;}
   if(backup!=null)Files.deleteIfExists(backup);
   throw failed;
  }
  if(backup!=null)Files.deleteIfExists(backup);
 }
}
