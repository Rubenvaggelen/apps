package com.theone.series.tv;

import java.util.*;

/**
 * Find alternate provider variants of the same series before fetching episodes.
 * Xtream providers may identify the same show with different series/TMDB IDs
 * and minor spelling changes (MobLand vs Mob Land). Only match normalized titles,
 * preserve every distinct provider ID and never match unrelated shows.
 */
final class SeriesSourceMatcher {
 private SeriesSourceMatcher(){}
 static String titleKey(String title){
  return CatalogEntry.normalize(title).replace(" ","");
 }
 static List<CatalogEntry> sources(CatalogEntry selected,Map<String,List<CatalogEntry>> groups){
  LinkedHashMap<Integer,CatalogEntry> unique=new LinkedHashMap<>();
  if(selected==null||!"series".equals(selected.type))return new ArrayList<>();
  unique.put(selected.id,selected);
  String name=titleKey(selected.title);
  boolean selectedSecondary=selected.id>=1000000000;
  // Media Player retrieves an entry and an alternate provider. Do not fetch
  // every quality/resolution duplicate: on small Google TV sticks that can
  // allocate large episode JSON structures and stall the remote-control UI.
  if(groups!=null)for(List<CatalogEntry> group:groups.values()){
   if(group==null)continue;
   for(CatalogEntry e:group){
    if(e==null||!"series".equals(e.type)||e.id==selected.id)continue;
    boolean secondary=e.id>=1000000000;
    if(secondary==selectedSecondary)continue;
    if(!titleKey(e.title).equals(name))continue;
    unique.putIfAbsent(e.id,e);
    return new ArrayList<>(unique.values());
   }
  }
  return new ArrayList<>(unique.values());
 }
}
