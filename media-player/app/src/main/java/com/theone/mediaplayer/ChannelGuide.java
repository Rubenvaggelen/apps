package com.theone.mediaplayer;

import java.text.Normalizer;
import java.util.Locale;

/** Presentation-only guide. Never changes provider IDs or streams. */
public final class ChannelGuide {
    private static final String[] ZIGGO = {
        "npo 1","npo 2","npo 3","rtl 4","rtl 5","sbs 6","rtl 7",
        "veronica","net 5","rtl 8","star channel","rtl z","viaplay tv",
        "ziggo sport","comedy central","paramount network","discovery","espn",
        "sbs9","id","tlc","bbc nl","mtv","24 kitchen","eurosport 1","hgtv",
        "national geographic","animal planet","history","nickelodeon","cartoon network",
        "bbc first","bbc one","bbc two","ard","zdf","canvas","een"
    };
    private ChannelGuide() {}

    public static String normalize(String text) {
        if(text == null)return "";
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
            .replaceAll("\\p{M}","").replaceAll("[^a-z0-9]+"," ").trim();
    }
    private static boolean includes(String normalized,String token) {
        return (" "+normalized+" ").contains(" "+token+" ");
    }
    public static boolean isAdult(String category,String title) {
        String t=" "+normalize(category+" "+title)+" ";
        for(String marker:new String[]{" xxx "," adult "," porn "," porno "," erotica "," erotic ",
             " 18plus "," 18 "," sex "," seks "," volwassenen "," hard core "," hardcore "}) {
            if(t.contains(marker))return true;
        }
        return false;
    }
    /** Provider naming may vary: Multi Lang, Multi Langl, Multilanguage. */
    public static boolean isTop2026MultiLang(String category) {
        String name = normalize(category);
        return name.contains("2026")
            && name.contains("top")
            && (name.contains("multi lang") || name.contains("multilang"));
    }

    /** Match the same live channel across country/quality tags, retaining channel numbers. */
    public static String channelIdentity(String name) {
        String text = normalize(name);
        // Prefixes such as "NL | Cartoon Network" must not block provider fallback.
        text = text.replaceAll("^(nl|nederland|netherlands|be|belgie|de|uk|us|fr)\\s+", "");
        text = text.replaceAll("\\b(hd|fhd|sd|fullhd|1080p|720p|50fps|60fps|hevc|h265|h264)\\b", " ");
        text = text.replaceAll("\\b(nl|nederland|netherlands)\\s*$", "").trim();
        text = text.replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? normalize(name) : text;
    }

    public static int videoTier(String title) {
        String t=normalize(title);
        if(includes(t,"16k"))return 3;
        if(includes(t,"8k"))return 2;
        if(includes(t,"4k")||includes(t,"uhd"))return 1;
        return 0;
    }
    public static int ziggoOrder(String title) {
        String n=normalize(title).replaceAll("\\b(nl|nederland|netherlands|hd|fhd|sd|hevc|h265|50fps|60fps)\\b"," ")
            .replaceAll(" +"," ").trim();
        for(int i=0;i<ZIGGO.length;i++) {
            if(n.equals(ZIGGO[i])||n.startsWith(ZIGGO[i]+" ")||n.endsWith(" "+ZIGGO[i]))return i;
        }
        return 10000;
    }
    public static String country(String title,String category) {
        String raw=(category+" "+title).toLowerCase(Locale.ROOT);
        String n=normalize(category+" "+title);
        if(raw.contains("[nl]")||raw.contains("|nl|")||raw.contains("nl:")||
          includes(n,"nederland")||includes(n,"netherlands")||includes(n,"dutch")||
          includes(n,"nl"))return "Nederland";
        if(raw.contains("[be]")||includes(n,"be")||includes(n,"belgie")||includes(n,"belgium")||includes(n,"vlaanderen"))return "België";
        if(raw.contains("[de]")||includes(n,"de")||includes(n,"germany")||includes(n,"deutschland")||includes(n,"duitsland"))return "Duitsland";
        if(raw.contains("[uk]")||includes(n,"uk")||includes(n,"england")||includes(n,"united kingdom"))return "Verenigd Koninkrijk";
        if(raw.contains("[us]")||includes(n,"usa")||includes(n,"united states")||includes(n,"us"))return "Verenigde Staten";
        if(raw.contains("[fr]")||includes(n,"fr")||includes(n,"france")||includes(n,"frankrijk"))return "Frankrijk";
        if(raw.contains("[tr]")||includes(n,"tr")||includes(n,"turkey")||includes(n,"turkiye"))return "Turkije";
        if(raw.contains("[es]")||includes(n,"es")||includes(n,"spain")||includes(n,"espana"))return "Spanje";
        if(raw.contains("[it]")||includes(n,"it")||includes(n,"italy")||includes(n,"italia"))return "Italië";
        if(raw.contains("[pt]")||includes(n,"pt")||includes(n,"portugal"))return "Portugal";
        if(raw.contains("[sr]")||includes(n,"sr")||includes(n,"suriname"))return "Suriname";
        if(ziggoOrder(title)<10000)return "Nederland";
        return "Overige";
    }
}
