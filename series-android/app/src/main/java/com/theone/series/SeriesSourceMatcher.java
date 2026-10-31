package com.theone.series;

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
  if(selected==null)return new ArrayList<>();
  if("series".equals(selected.type))unique.put(selected.id,selected);
  String name=titleKey(selected.title);
  if(groups!=null)for(List<CatalogEntry> group:groups.values()){
   if(group==null)continue;
   for(CatalogEntry e:group){
    if(e==null||!"series".equals(e.type)||!titleKey(e.title).equals(name))continue;
    unique.putIfAbsent(e.id,e);
    // Avoid excessive unrelated duplicates being queried due to provider catalogs.
    if(unique.size()>=12)return new ArrayList<>(unique.values());
   }
  }
  return new ArrayList<>(unique.values());
 }
}
