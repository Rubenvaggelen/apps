package com.theone.mediaplayer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ChannelOrganizer {
    private static final String[] NL_ORDER = {
        "npo 1","npo 2","npo 3","rtl 4","rtl 5","sbs 6","rtl 7",
        "veronica","net 5","rtl 8","star channel","rtl z","viaplay tv","ziggo sport"
    };
    static String country(String title, String category) {
        String t=(" "+title+" "+category+" ").toLowerCase(Locale.ROOT);
        if(ziggoPosition(title)!=Integer.MAX_VALUE)return "Nederland";
        if(matches(t,"|nl|","[nl]"," nederland "," dutch "," netherlands "," nl:","nl |"))return "Nederland";
        if(matches(t,"|be|","[be]"," belgie "," belgium "," vlaanderen ","be |"))return "België";
        if(matches(t,"|de|","[de]"," germany "," deutschland "," duitsland ","de |"))return "Duitsland";
        if(matches(t,"|uk|","[uk]"," united kingdom "," great britain "," uk |"," england "))return "Verenigd Koninkrijk";
        if(matches(t,"|us|","[us]"," usa "," united states ","us |"))return "Verenigde Staten";
        if(matches(t,"|fr|","[fr]"," france "," frankrijk ","fr |"))return "Frankrijk";
        if(matches(t,"|tr|","[tr]"," turkey "," turkiye "," turkije ","tr |"))return "Turkije";
        if(matches(t,"|es|","[es]"," spain "," espana "," spanje ","es |"))return "Spanje";
        if(matches(t,"|it|","[it]"," italy "," italia "," italie ","it |"))return "Italië";
        if(matches(t,"|pt|","[pt]"," portugal ","pt |"))return "Portugal";
        if(matches(t,"|sr|","[sr]"," suriname "," surinaams "))return "Suriname";
        return "Overige";
    }
    static List<String> countries(List<XtreamCatalog.Entry> items) {
        java.util.TreeSet<String> seen=new java.util.TreeSet<>();
        for(XtreamCatalog.Entry item:items)seen.add(country(item.title,item.category));
        List<String> result=new ArrayList<>(seen);
        if(result.remove("Nederland"))result.add(0,"Nederland");
        if(result.remove("Overige"))result.add("Overige");
        return result;
    }
    static List<XtreamCatalog.Entry> forCountry(List<XtreamCatalog.Entry> items,String target) {
        List<XtreamCatalog.Entry> filtered=new ArrayList<>();
        for(XtreamCatalog.Entry item:items) {
            String assigned=country(item.title,item.category);
            if("Alle landen".equals(target)||target.equals(assigned))filtered.add(item);
        }
        Collections.sort(filtered,Comparator.comparingInt((XtreamCatalog.Entry e)->qualityGroup(e.title))
            .thenComparingInt(e->"Nederland".equals(target)?ziggoPosition(e.title):Integer.MAX_VALUE)
            .thenComparing(e->e.title.toLowerCase(Locale.ROOT)));
        return filtered;
    }
    static int qualityGroup(String title) {
        String t=normalize(title);
        if(t.matches(".*\\b16k\\b.*"))return 3;
        if(t.matches(".*\\b8k\\b.*"))return 2;
        if(t.matches(".*\\b4k\\b.*")||t.matches(".*\\buhd\\b.*"))return 1;
        return 0;
    }
    private static int ziggoPosition(String title) {
        String normalized=normalize(title).trim().replaceAll("\\b(?:nl|netherlands|nederland|hd|fhd|sd|hevc|h265|50fps|60fps)\\b"," ").replaceAll(" +"," ").trim();
        for(int i=0;i<NL_ORDER.length;i++) {
            String name=NL_ORDER[i];
            if(normalized.equals(name)||normalized.startsWith(name+" ")||normalized.endsWith(" "+name))return i+1;
        }
        return Integer.MAX_VALUE;
    }
    private static String normalize(String raw) {
        String t=Normalizer.normalize(raw.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","");
        return t.replaceAll("[^a-z0-9]+"," ").trim();
    }
    private static boolean matches(String t,String... parts) {
        for(String p:parts)if(t.contains(p))return true;
        return false;
    }
}
