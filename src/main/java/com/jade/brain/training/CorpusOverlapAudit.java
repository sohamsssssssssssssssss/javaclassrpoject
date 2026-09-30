package com.jade.brain.training;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.*;
import java.util.*;

/** Deterministic lexical overlap, not semantic equivalence or a comprehensive contamination audit. */
public final class CorpusOverlapAudit {
    private CorpusOverlapAudit() {}
    public record Result(long exactLines,long exactParagraphs,long normalizedSentences,long similarParagraphs,
                         long comparedParagraphPairs,List<String> examples) {
        public Result { examples=List.copyOf(examples); }
    }
    record Passage(int partition,String file,String text,Set<String> shingles) {}
    public static Result audit(EnglishCorpus.Manifest manifest,EnglishCorpus.Split split) throws IOException {
        if(!EnglishCorpus.split(manifest,split.trainFraction(),split.validationFraction()).equals(split))
            throw new IllegalArgumentException("Noncanonical overlap split");
        if(manifest.bytes()>2*1024*1024)throw new IllegalArgumentException("Overlap audit byte budget exceeded");
        var lines=new HashMap<String,Integer>();var paragraphs=new HashMap<String,Integer>();var sentences=new HashMap<String,Integer>();
        var passages=new ArrayList<Passage>();var examples=new ArrayList<String>();
        var partitions=List.of(split.train(),split.validation(),split.test());
        for(int partition=0;partition<partitions.size();partition++) for(var entry:partitions.get(partition)) {
            var paragraph=new StringBuilder();
            try(var reader=Files.newBufferedReader(manifest.root().resolve(entry.path()),StandardCharsets.UTF_8)) {
                long characters=0;String line;
                while((line=reader.readLine())!=null){
                    characters+=line.length();if(characters>1_048_576 || line.length()>16384)throw new IllegalArgumentException("Overlap document bound exceeded");
                    if(!line.isBlank())lines.merge(line.strip(),1<<partition,(a,b)->a|b);
                    if(line.isBlank()) { addParagraph(paragraph,partition,entry.path(),paragraphs,sentences,passages);paragraph.setLength(0); }
                    else {if(!paragraph.isEmpty())paragraph.append('\n');paragraph.append(line);if(paragraph.length()>65536)throw new IllegalArgumentException("Overlap paragraph bound exceeded");}
                }
                addParagraph(paragraph,partition,entry.path(),paragraphs,sentences,passages);
            }
        }
        long similar=0,pairs=0;
        // ponytail: bounded pair scan (<2M); inverted shingle index if corpus exceeds this audit ceiling.
        for(int i=0;i<passages.size();i++) for(int j=i+1;j<passages.size();j++) {
            var a=passages.get(i);var b=passages.get(j);if(a.partition()==b.partition())continue;
            if(++pairs>2_000_000)throw new IllegalArgumentException("Similarity audit pair budget exceeded");
            if(a.text().equals(b.text()) || a.shingles().isEmpty() || b.shingles().isEmpty())continue;
            int smaller=Math.min(a.shingles().size(),b.shingles().size()),larger=Math.max(a.shingles().size(),b.shingles().size());
            if(smaller/(double)larger<.8)continue;
            long shared=a.shingles().stream().filter(b.shingles()::contains).count();
            double similarity=shared/(double)(a.shingles().size()+b.shingles().size()-shared);
            if(similarity>=.8){similar++;if(examples.size()<10)examples.add(a.file()+" <> "+b.file()+" Jaccard="+similarity);}
        }
        for(var item:sentences.entrySet().stream().filter(e->Integer.bitCount(e.getValue())>1).sorted(Map.Entry.comparingByKey()).limit(10).toList())
            examples.add("shared normalized sentence: "+item.getKey());
        return new Result(shared(lines),shared(paragraphs),shared(sentences),similar,pairs,examples);
    }
    private static void addParagraph(StringBuilder value,int partition,String file,Map<String,Integer> paragraphs,
                                     Map<String,Integer> sentences,List<Passage> passages) {
        if(value.isEmpty())return;String text=value.toString().strip();paragraphs.merge(text,1<<partition,(a,b)->a|b);
        var iterator=BreakIterator.getSentenceInstance(Locale.ENGLISH);iterator.setText(text);
        for(int from=iterator.first(),to=iterator.next();to!=BreakIterator.DONE;from=to,to=iterator.next()){
            String sentence=normalize(text.substring(from,to));if(!sentence.isEmpty())sentences.merge(sentence,1<<partition,(a,b)->a|b);
        }
        if(passages.size()>=4096)throw new IllegalArgumentException("Overlap passage budget exceeded");
        String[] words=normalize(text).split(" ");var shingles=new HashSet<String>();
        if(words.length>=20)for(int i=0;i+5<=words.length;i++)shingles.add(String.join(" ",Arrays.copyOfRange(words,i,i+5)));
        passages.add(new Passage(partition,file,text,Set.copyOf(shingles)));
    }
    static String normalize(String value) { return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+"," ").strip(); }
    private static long shared(Map<String,Integer> values){return values.values().stream().filter(mask->Integer.bitCount(mask)>1).count();}

    public static void writeManifest(EnglishCorpus.Manifest manifest,Path provenance,Path output) throws IOException {
        if(Files.size(provenance)>1_048_576)throw new IllegalArgumentException("Provenance byte budget exceeded");
        var sources=new HashMap<String,String[]>();
        try(var reader=Files.newBufferedReader(provenance,StandardCharsets.UTF_8)){
            reader.readLine();String line;while((line=reader.readLine())!=null){var fields=line.split("\t",-1);
                if(fields.length!=7 || Arrays.stream(fields).anyMatch(String::isBlank) || sources.put(fields[0],fields)!=null)
                    throw new IllegalArgumentException("Invalid or duplicate provenance record");}
        }
        var result=new StringBuilder("relative_path\tcategory\tbytes\tcode_points\tdigest\tprovenance_type\tsource\n");
        for(var file:manifest.files()){
            var source=sources.remove(file.path());if(source==null)throw new IllegalArgumentException("Missing provenance for "+file.path());
            result.append(String.join("\t",file.path(),source[1],Long.toString(file.bytes()),Long.toString(file.codePoints()),file.digest(),source[2],source[3])).append('\n');
        }
        if(!sources.isEmpty())throw new IllegalArgumentException("Provenance names non-corpus documents");
        Files.writeString(output,result,StandardCharsets.UTF_8);
    }
}
