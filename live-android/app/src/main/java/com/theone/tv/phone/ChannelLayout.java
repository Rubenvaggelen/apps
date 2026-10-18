package com.theone.tv.phone;

import java.text.Normalizer;
import java.util.*;

/**
 * Live channel presentation rules aligned with The One Media Player.
 * This only organizes channel cards. Provider identities and playback URLs stay intact.
 */
public final class ChannelLayout {
    public static final String[] CODES={"NL","BE","DE","GB","US","FR","TR","ES","IT","PT","SR","OTHER","ALL"};
    public static final String[] NAMES={"Nederland","België","Duitsland","Verenigd Koninkrijk",
        "Verenigde Staten","Frankrijk","Turkije","Spanje","Italië","Portugal","Suriname",
        "Overige","Alle landen"};
    private static final String[] ZIGGO={
        "npo 1","npo 2","npo 3","rtl 4","rtl 5","sbs 6","rtl 7",
        "veronica","net 5","rtl 8","star channel","rtl z","viaplay tv",
        "ziggo sport","comedy central","paramount network","discovery","espn",
        "sbs9","id","tlc","bbc nl","mtv","24 kitchen","eurosport 1","hgtv",
        "national geographic","animal planet","history","nickelodeon","cartoon network",
        "bbc first","bbc one","bbc two","ard","zdf","canvas","een"
    };
    private ChannelLayout(){}

    public static String normalize(String text){
        if(text==null)return "";
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
            .replaceAll("\\p{M}","").replaceAll("[^a-z0-9]+"," ").trim();
    }
    private static boolean includes(String value,String token){
        return (" "+value+" ").contains(" "+token+" ");
    }
    public static boolean hasNlPrefix(String name){
        if(name==null)return false;
        String n=name.trim();
        return n.length()>=2 && n.regionMatches(true,0,"NL",0,2)
            && (n.length()==2 || !Character.isLetterOrDigit(n.charAt(2)));
    }
    public static String channelIdentity(String name){
        String text=normalize(name);
        text=text.replaceAll("^(nl|nederland|netherlands|be|belgie|de|uk|us|fr)\\s+","");
        text=text.replaceAll("\\b(hd|fhd|sd|fullhd|1080p|720p|50fps|60fps|hevc|h265|h264)\\b"," ");
        text=text.replaceAll("\\b(nl|nederland|netherlands)\\s*$","").trim();
        text=text.replaceAll("\\s+"," ").trim();
        return text.isEmpty()?normalize(name):text;
    }
    public static int videoTier(String name){
        String n=normalize(name);
        if(includes(n,"16k"))return 3;
        if(includes(n,"8k"))return 2;
        if(includes(n,"4k")||includes(n,"uhd"))return 1;
        return 0;
    }
    public static int ziggoOrder(String title){
        String n=normalize(title).replaceAll("\\b(nl|nederland|netherlands|hd|fhd|sd|hevc|h265|50fps|60fps)\\b"," ")
            .replaceAll(" +"," ").trim();
        for(int i=0;i<ZIGGO.length;i++)
            if(n.equals(ZIGGO[i])||n.startsWith(ZIGGO[i]+" ")||n.endsWith(" "+ZIGGO[i]))return i;
        return 10000;
    }
    /** Same geography rules as Media Player; NL selection remains prefix-only. */
    public static String country(String title,String category){
        String raw=(category+" "+title).toLowerCase(Locale.ROOT);
        String n=normalize(category+" "+title);
        if(raw.contains("[nl]")||raw.contains("|nl|")||raw.contains("nl:")||
          includes(n,"nederland")||includes(n,"netherlands")||includes(n,"dutch")||
          includes(n,"nl"))return "NL";
        if(raw.contains("[be]")||includes(n,"be")||includes(n,"belgie")||includes(n,"belgium")||includes(n,"vlaanderen"))return "BE";
        if(raw.contains("[de]")||includes(n,"de")||includes(n,"germany")||includes(n,"deutschland")||includes(n,"duitsland"))return "DE";
        if(raw.contains("[uk]")||includes(n,"uk")||includes(n,"england")||includes(n,"united kingdom"))return "GB";
        if(raw.contains("[us]")||includes(n,"usa")||includes(n,"united states")||includes(n,"us"))return "US";
        if(raw.contains("[fr]")||includes(n,"fr")||includes(n,"france")||includes(n,"frankrijk"))return "FR";
        if(raw.contains("[tr]")||includes(n,"tr")||includes(n,"turkey")||includes(n,"turkiye"))return "TR";
        if(raw.contains("[es]")||includes(n,"es")||includes(n,"spain")||includes(n,"espana"))return "ES";
        if(raw.contains("[it]")||includes(n,"it")||includes(n,"italy")||includes(n,"italia"))return "IT";
        if(raw.contains("[pt]")||includes(n,"pt")||includes(n,"portugal"))return "PT";
        if(raw.contains("[sr]")||includes(n,"sr")||includes(n,"suriname"))return "SR";
        if(ziggoOrder(title)<10000)return "NL";
        return "OTHER";
    }
    public static String countryName(String code){
        for(int i=0;i<CODES.length;i++)if(CODES[i].equals(code))return NAMES[i];
        return NAMES[NAMES.length-2];
    }
    private static int order(String code){
        for(int i=0;i<CODES.length;i++)if(CODES[i].equals(code))return i;
        return CODES.length;
    }
    public static final class Layout {
        public final Map<String,List<CatalogEntry>> countries;
        public final Map<String,List<CatalogEntry>> alternativeStreams;
        Layout(Map<String,List<CatalogEntry>> c,Map<String,List<CatalogEntry>> v){
            countries=c;alternativeStreams=v;
        }
    }
    public static Layout prepare(List<CatalogEntry> entries,Map<String,List<CatalogEntry>> existingVariants){
        Map<String,LinkedHashMap<String,CatalogEntry>> grouped=new LinkedHashMap<>();
        Map<String,LinkedHashMap<Integer,CatalogEntry>> streams=new LinkedHashMap<>();
        for(String code:CODES)grouped.put(code,new LinkedHashMap<>());
        for(CatalogEntry e:entries){
            if(e.isAdult())continue;
            String code=country(e.title,CatalogEntry.countryName(e.category));
            String identity=channelIdentity(e.title);
            for(String bucket:new String[]{"ALL",code}){
                if(bucket.equals("NL")&&!hasNlPrefix(e.title))continue;
                LinkedHashMap<String,CatalogEntry> group=grouped.get(bucket);
                if(group==null)continue;
                String key=bucket+"|"+identity;
                CatalogEntry winner=group.get(identity);
                if(winner==null){group.put(identity,e);winner=e;}
                LinkedHashMap<Integer,CatalogEntry> values=streams.computeIfAbsent(winner.key(),k->new LinkedHashMap<>());
                List<CatalogEntry> variants=existingVariants.get(e.key());
                if(variants==null)variants=Collections.singletonList(e);
                for(CatalogEntry variant:variants)values.putIfAbsent(variant.id,variant);
            }
        }
        Map<String,List<CatalogEntry>> countries=new LinkedHashMap<>();
        for(String bucket:CODES){
            List<CatalogEntry> values=new ArrayList<>(grouped.get(bucket).values());
            values.sort((a,b)->{
                String ca=country(a.title,CatalogEntry.countryName(a.category));
                String cb=country(b.title,CatalogEntry.countryName(b.category));
                if(bucket.equals("ALL")){
                    int byCountry=Integer.compare(order(ca),order(cb));
                    if(byCountry!=0)return byCountry;
                }
                int quality=Integer.compare(videoTier(a.title),videoTier(b.title));
                if(quality!=0)return quality;
                if(bucket.equals("NL")||bucket.equals("ALL")&&ca.equals("NL")&&cb.equals("NL")){
                    int ziggo=Integer.compare(ziggoOrder(a.title),ziggoOrder(b.title));
                    if(ziggo!=0)return ziggo;
                }
                return a.title.compareToIgnoreCase(b.title);
            });
            countries.put(bucket,Collections.unmodifiableList(values));
        }
        Map<String,List<CatalogEntry>> choices=new HashMap<>();
        for(Map.Entry<String,LinkedHashMap<Integer,CatalogEntry>> item:streams.entrySet())
            choices.put(item.getKey(),new ArrayList<>(item.getValue().values()));
        return new Layout(countries,choices);
    }
}
