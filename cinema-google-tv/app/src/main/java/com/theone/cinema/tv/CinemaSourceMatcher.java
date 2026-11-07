package com.theone.cinema.tv;

import java.util.*;
import java.util.regex.*;

/** Bounded alternate movie versions across Xtream catalogs with different
 * stream/TMDB IDs. Prefer the currently useful secondary provider. */
final class CinemaSourceMatcher {
 private CinemaSourceMatcher(){}
 private static final Pattern YEAR=Pattern.compile("(?:^|\\s)((?:19|20)\\d{2})$");
 private static String normalized(String title){return CatalogEntry.normalize(title);}
 private static String year(String title){
  Matcher m=YEAR.matcher(normalized(title));
  return m.find()?m.group(1):"";
 }
 private static String titleKey(String title){
  return YEAR.matcher(normalized(title)).replaceFirst("").replace(" ","").trim();
 }
 private static boolean sameFilm(CatalogEntry first,CatalogEntry second){
  if(first==null||second==null||!first.type.equals(second.type))return false;
  String y1=year(first.title),y2=year(second.title);
  if(!y1.isEmpty()&&!y2.isEmpty()&&!y1.equals(y2))return false;
  if(!first.movieId.isEmpty()&&first.movieId.equals(second.movieId))return true;
  String key=titleKey(first.title);
  return key.length()>=4&&key.equals(titleKey(second.title));
 }
 static List<CatalogEntry> sources(CatalogEntry selected,Map<String,List<CatalogEntry>> variants,
      String preferredLanguage,int maxHeight){
  LinkedHashMap<Integer,CatalogEntry> found=new LinkedHashMap<>();
  if(selected==null)return new ArrayList<>();
  found.put(selected.id,selected);
  int secondCount=selected.id>=1000000000?1:0;
  int primaryCount=selected.id<1000000000?1:0;
  if(variants!=null){
   List<CatalogEntry> nativeGroup=variants.get(selected.key());
   if(nativeGroup!=null)for(CatalogEntry candidate:nativeGroup){
    if(candidate==null||!sameFilm(selected,candidate))continue;
    boolean secondary=candidate.id>=1000000000;
    if((secondary?secondCount:primaryCount)>=2||found.containsKey(candidate.id))continue;
    found.put(candidate.id,candidate);
    if(secondary)secondCount++;else primaryCount++;
   }
   outer:for(List<CatalogEntry> group:variants.values()){
    if(group==null)continue;
    for(CatalogEntry candidate:group){
     if(candidate==null||found.containsKey(candidate.id)||!sameFilm(selected,candidate))continue;
     boolean secondary=candidate.id>=1000000000;
     if((secondary?secondCount:primaryCount)>=2)continue;
     found.put(candidate.id,candidate);
     if(secondary)secondCount++;else primaryCount++;
     if(secondCount>=2&&primaryCount>=2)break outer;
    }
   }
  }
  List<CatalogEntry> results=new ArrayList<>(found.values());
  results.sort(Comparator.comparingInt((CatalogEntry e)->e.id>=1000000000?0:1)
       .thenComparing(CatalogEntry.playbackOrder(preferredLanguage,maxHeight)));
  return results;
 }
}
