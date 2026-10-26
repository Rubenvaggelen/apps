package com.theone.series.tv;
import org.json.*;import java.util.*;import java.util.regex.*;
final class EpisodeCatalog {
 static List<CatalogEntry> parse(JSONObject data,CatalogEntry parent,String base,boolean second)throws Exception {
  List<CatalogEntry> out=new ArrayList<>();JSONObject seasons=data.optJSONObject("episodes");if(seasons==null)return out;
  Iterator<String> keys=seasons.keys();while(keys.hasNext()){String seasonKey=keys.next();int season;try{season=Integer.parseInt(seasonKey);}catch(NumberFormatException ignored){continue;}if(season<0)continue;JSONArray list=seasons.optJSONArray(seasonKey);if(list==null)continue;
   for(int i=0;i<list.length();i++){JSONObject row=list.optJSONObject(i);if(row==null)continue;long raw=row.optLong("id",0);if(raw<=0||raw>=1000000000L)continue;int number=row.optInt("episode_num",i+1);if(number<1)number=i+1;
    JSONObject info=row.optJSONObject("info");String poster=info==null?"":PosterMetadata.choose(info,base);if(poster.isEmpty())poster=parent.poster;
    String name=row.optString("title","").trim();if(name.isEmpty())name="Aflevering "+number;String title=String.format(Locale.ROOT,"S%02dE%02d · %s",season,number,name)+(parent.rank>0?" · "+parent.rank+"p":"");
    Matcher label=Pattern.compile("(?i)S(\\d{1,3})[ ._-]*E(\\d{1,4})|(?<!\\d)(\\d{1,3})x(\\d{1,4})(?!\\d)").matcher(name);int actualSeason=season;if(label.find()){actualSeason=Integer.parseInt(label.group(1)!=null?label.group(1):label.group(3));number=Integer.parseInt(label.group(2)!=null?label.group(2):label.group(4));title=String.format(Locale.ROOT,"S%02dE%02d · %s",actualSeason,number,name)+(parent.rank>0?" · "+parent.rank+"p":"");}
    // Keep the same season/episode together across providers even if they use
    // different series IDs or TMDB IDs (e.g. MobLand / Mob Land).
    // This mirrors the cross-provider episode alternatives in Media Player.
    String seriesTitle=CatalogEntry.normalize(parent.title).replace(" ","");
    String group="series-episode:"+seriesTitle+":"+actualSeason+":"+number;out.add(new CatalogEntry((int)raw+(second?1000000000:0),title,row.optString("container_extension","mp4"),poster,actualSeason+":"+number,"episode",parent.added,"",group,CatalogEntry.language(parent.title)));
   }
  }return out;
 }
 static Result merge(List<CatalogEntry> all){Map<String,List<CatalogEntry>> variants=new LinkedHashMap<>();for(CatalogEntry e:all)variants.computeIfAbsent(e.key(),k->new ArrayList<>()).add(e);TreeMap<Integer,List<CatalogEntry>> seasons=new TreeMap<>();for(List<CatalogEntry> xs:variants.values()){xs.sort(CatalogEntry.PLAYBACK_ORDER);CatalogEntry e=xs.get(0);int season=Integer.parseInt(e.category.split(":")[0]);seasons.computeIfAbsent(season,k->new ArrayList<>()).add(e);}for(List<CatalogEntry> xs:seasons.values())xs.sort(Comparator.comparingInt(e->Integer.parseInt(e.category.split(":")[1])));return new Result(seasons,variants);}
 static final class Result {final SortedMap<Integer,List<CatalogEntry>> seasons;final Map<String,List<CatalogEntry>> variants;Result(SortedMap<Integer,List<CatalogEntry>> s,Map<String,List<CatalogEntry>> v){seasons=s;variants=v;}}
}
