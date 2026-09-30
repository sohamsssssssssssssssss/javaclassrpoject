package com.jade.brain.generation;

import java.util.*;
import java.util.regex.Pattern;

/** Cheap output descriptors. They are not measures of understanding or answer quality. */
public final class EnglishOutputMetrics {
    private EnglishOutputMetrics() {}
    public record Result(long codePoints,double printableRatio,double controlRatio,double wordLikeRatio,
                         double tokenRepetition,double immediateTokenRepetition,double repeatedWordTrigrams) {}
    public static Result measure(String text,List<Integer> ids) {
        Objects.requireNonNull(text);Objects.requireNonNull(ids);
        long points=text.codePointCount(0,text.length());
        long printable=text.codePoints().filter(cp->!Character.isISOControl(cp) && cp!=0xfffd).count();
        long controls=text.codePoints().filter(Character::isISOControl).count();
        String[] words=text.strip().isEmpty()?new String[0]:text.strip().split("\\s+");
        long wordLike=Arrays.stream(words).filter(w->Pattern.matches("[\\p{Punct}“”‘’]*[A-Za-z]+(?:['’][A-Za-z]+)*[\\p{Punct}“”‘’]*",w)).count();
        long repeated=0;for(int i=1;i<ids.size();i++)if(ids.get(i).equals(ids.get(i-1)))repeated++;
        var seen=new HashSet<String>();long repeatedGrams=0;int grams=Math.max(0,words.length-2);
        for(int i=0;i<grams;i++){String gram=String.join(" ",Arrays.copyOfRange(words,i,i+3)).toLowerCase(Locale.ROOT);if(!seen.add(gram))repeatedGrams++;}
        return new Result(points,ratio(printable,points),ratio(controls,points),ratio(wordLike,words.length),
                ratio(ids.size()-new HashSet<>(ids).size(),ids.size()),
                ratio(repeated,Math.max(0,ids.size()-1)),ratio(repeatedGrams,grams));
    }
    private static double ratio(long numerator,long denominator){return denominator==0?0:numerator/(double)denominator;}
}
