package com.theone.mediaplayer;

import java.text.Normalizer;
import java.util.Locale;

/** Shared title matching for the Media Player film/series search. */
final class CatalogTitleSearch {
    private CatalogTitleSearch() {}

    static String normalize(String value) {
        String text=value==null?"":value.toLowerCase(Locale.ROOT);
        text=Normalizer.normalize(text,Normalizer.Form.NFD);
        text=text.replaceAll("\\p{M}+","");
        // Queen's, Queen’s and Queens are the same searchable word.
        // Treat apostrophes *inside* words as joiners, not word boundaries.
        text=text.replaceAll("(?<=[a-z0-9])['\u2019\u2018\u02bc](?=[a-z0-9])","");
        return text.replaceAll("[^a-z0-9]+"," ").trim();
    }

    static boolean matches(String title,String query) {
        return matchesNormalized(title,normalize(query));
    }

    static boolean matchesNormalized(String title,String normalizedQuery) {
        if(normalizedQuery==null||normalizedQuery.isEmpty())return true;
        String name=normalize(title);
        for(String word:normalizedQuery.split("\\s+")){
            if(word.isEmpty()||name.contains(word))continue;
            // A search typo must not hide an existing series. Only consider
            // words of five or more letters and a single insertion/deletion/
            // substitution; shorter queries stay exact to avoid noisy results.
            boolean close=false;
            if(word.length()>=5){
                for(String titleWord:name.split("\\s+")){
                    if(titleWord.length()>=5 && editDistanceAtMostOne(word,titleWord)){
                        close=true;
                        break;
                    }
                }
            }
            if(!close)return false;
        }
        return true;
    }

    private static boolean editDistanceAtMostOne(String left,String right){
        int a=left.length(),b=right.length();
        if(Math.abs(a-b)>1)return false;
        int i=0,j=0,errors=0;
        while(i<a&&j<b){
            if(left.charAt(i)==right.charAt(j)){i++;j++;continue;}
            if(++errors>1)return false;
            if(a>b)i++;
            else if(b>a)j++;
            else {i++;j++;}
        }
        return errors+(a-i)+(b-j)<=1;
    }
}
