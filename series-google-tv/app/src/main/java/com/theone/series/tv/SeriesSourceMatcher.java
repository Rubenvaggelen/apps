package com.theone.series.tv;

import java.util.*;
import java.util.regex.*;

/** Select a bounded set of matching provider variants; prioritize the last
 * known playable secondary provider without merging unrelated seasons/remakes. */
final class SeriesSourceMatcher {
 private SeriesSourceMatcher(){}
 private static final Pattern YEAR=Pattern.compile("(?:^|\\s)((?:19|20)\\d{2})$");
 private static String normalized(String title){return CatalogEntry.normalize(title);}
 private static String year(String title){
  Matcher match=YEAR.matcher(normalized(title));
  return match.find()?match.group(1):"";
 }
 static String titleKey(String title){
  return YEAR.matcher(normalized(title)).replaceFirst("").replace(" ","").trim();
 }
 private static boolean sameSeries(CatalogEntry a,CatalogEntry b){
  if(a==null||b==null||!"series".equals(a.type)||!"series".equals(b.type))return false;
  // Both remakes have explicit, contradictory years: do not cross-match.
  String y1=year(a.title),y2=year(b.title);
  if(!y1.isEmpty()&&!y2.isEmpty()&&!y1.equals(y2))return false;
  if(!a.movieId.isEmpty()&&a.movieId.equals(b.movieId))return true;
  String name=titleKey(a.title);
  return name.length()>=4&&name.equals(titleKey(b.title));
 }
 static List<CatalogEntry> sources(CatalogEntry selected,Map<String,List<CatalogEntry>> groups){
  LinkedHashMap<Integer,CatalogEntry> unique=new LinkedHashMap<>();
  if(selected==null||!"series".equals(selected.type))return new ArrayList<>();
  unique.put(selected.id,selected);
  boolean selectedSecondary=selected.id>=1000000000;
  if(groups!=null)outer:for(List<CatalogEntry> group:groups.values()){
   if(group==null)continue;
   for(CatalogEntry candidate:group){
    if(candidate==null||candidate.id==selected.id)continue;
    if((candidate.id>=1000000000)==selectedSecondary)continue;
    if(!sameSeries(selected,candidate))continue;
    unique.putIfAbsent(candidate.id,candidate);
    break outer; // One working alternative provider per episode: bounded TV memory.
   }
  }
  List<CatalogEntry> result=new ArrayList<>(unique.values());
  result.sort(Comparator.comparingInt(e->e.id>=1000000000?0:1));
  return result;
 }
}
