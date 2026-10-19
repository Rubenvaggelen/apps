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
            if(!word.isEmpty()&&!name.contains(word))return false;
        }
        return true;
    }
}
