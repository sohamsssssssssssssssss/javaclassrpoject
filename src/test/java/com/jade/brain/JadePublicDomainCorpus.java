package com.jade.brain;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Explicit corpus preparation; never called by the training path. */
public class JadePublicDomainCorpus {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]); Files.createDirectories(root);
  String[][] sources={
   {"01-austen.txt","1342","dialogue/social narrative","Jane Austen (1817)","Pride and Prejudice"},
   {"02-darwin.txt","1228","scientific explanation/cause and effect","Charles Darwin (1882)","On the Origin of Species"},
   {"03-franklin.txt","148","autobiography/practical reasoning","Benjamin Franklin (1790); editor C. W. Eliot (1926)","Autobiography"},
   {"04-thoreau.txt","205","descriptive/philosophical prose","Henry David Thoreau (1862)","Walden"},
   {"05-carroll.txt","11","dialogue/questions/fantasy","Lewis Carroll (1898)","Alice's Adventures in Wonderland"},
   {"06-machiavelli.txt","1232","comparison/argument/explanation","Niccolo Machiavelli (1527); translator W. K. Marriott (1945)","The Prince"},
   {"07-douglass.txt","23","historical narrative/factual prose","Frederick Douglass (1895)","Narrative of the Life"},
   {"08-melville.txt","2701","descriptive narrative/definitions","Herman Melville (1891)","Moby Dick"},
   {"10-doyle.txt","1661","validation: independent detective dialogue","Arthur Conan Doyle (1930)","The Adventures of Sherlock Holmes"},
   {"11-wells.txt","35","test: independent speculative explanation","H. G. Wells (1946)","The Time Machine"}};
  var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
  var metadata=new StringBuilder("path\tcategory\tprovenance\tsource\tauthor_death\tsource_sha256\textraction\n");
  for(var source:sources){
   String url="https://www.gutenberg.org/cache/epub/"+source[1]+"/pg"+source[1]+".txt";
   var response=client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
   if(response.statusCode()!=200)throw new java.io.IOException("HTTP "+response.statusCode()+" for "+url);
   byte[] raw;try(var body=response.body()){raw=body.readNBytes(2_000_001);}if(raw.length>2_000_000)throw new IllegalArgumentException("Source exceeds 2 MB bound");
   String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString().replace("\r\n","\n");
   var start=Pattern.compile("(?m)^\\*\\*\\* START OF .*?\\*\\*\\*.*$",Pattern.CASE_INSENSITIVE).matcher(text);
   if(!start.find())throw new IllegalArgumentException("Missing Gutenberg body marker "+url);
   var end=Pattern.compile("(?m)^\\*\\*\\* END OF .*?\\*\\*\\*.*$",Pattern.CASE_INSENSITIVE).matcher(text);if(!end.find(start.end()))throw new IllegalArgumentException("Missing body end");
   String body=text.substring(start.end(),end.start());
   // Bounded deterministic interior excerpt avoids front matter, contents, credits and shared headers.
   int from=body.indexOf("\n\n",Math.min(20_000,body.length()/4));if(from<0)throw new IllegalArgumentException("No paragraph boundary");
   var result=new StringBuilder();int bytes=0;
   for(String paragraph:body.substring(from+2).split("\\n\\s*\\n")){
    String cleaned=paragraph.strip();if(cleaned.isEmpty())continue;
    if(cleaned.toLowerCase(Locale.ROOT).contains("project gutenberg"))continue;
    // Keep native paragraph/line structure; no rewriting or sentence/name substitution.
    String part=cleaned+"\n\n";int size=part.getBytes(StandardCharsets.UTF_8).length;
    if(bytes+size>30_720)break;result.append(part);bytes+=size;
   }
   if(bytes<25_600)throw new IllegalArgumentException("Excerpt too small: "+source[0]+" "+bytes);
   String excerpt=result.toString();
   String extraction="interior paragraphs after character 20000; <=30720 UTF-8 bytes; no Gutenberg boilerplate";
   if(source[0].equals("06-machiavelli.txt")){
    int dedication=excerpt.indexOf("DEDICATION\n");if(dedication<0)throw new IllegalArgumentException("Missing English excerpt boundary");
    excerpt=excerpt.substring(dedication);
    extraction+="; removed pre-dedication editorial front matter, Latin quotation and Italian bibliography for English-only corpus";
   }
   Files.writeString(root.resolve(source[0]),excerpt,StandardCharsets.UTF_8);
   String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
   metadata.append(String.join("\t",source[0],source[2],"public-domain historical English excerpt",url,source[3],sha,extraction)).append('\n');
   System.out.println(source[0]+" bytes="+excerpt.getBytes(StandardCharsets.UTF_8).length+" source="+url+" sha256="+sha);
  }
  metadata.append("09-modern-original.txt\tprocedures/technical/QA/everyday English\toriginal English authored for JADE Loop 5D\tlocal\tJADE experiment authorship 2026\tn/a\tindependent original prose; no repeated templates\n");
  Files.writeString(Path.of(args[1]),metadata,StandardCharsets.UTF_8);
 }
}
