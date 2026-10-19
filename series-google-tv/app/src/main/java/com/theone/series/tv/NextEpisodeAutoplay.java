package com.theone.series.tv;
import java.util.List;
import java.util.Map;

/** Select a next episode from the same loaded series, never from another show. */
final class NextEpisodeAutoplay {
 private NextEpisodeAutoplay(){}
 static CatalogEntry next(EpisodeCatalog.Result episodeCatalog,CatalogEntry current){
  if(episodeCatalog==null||current==null||!current.isEpisode())return null;
  boolean found=false;
  for(Map.Entry<Integer,List<CatalogEntry>> season:episodeCatalog.seasons.entrySet()){
   if(season.getValue()==null)continue;
   for(CatalogEntry episode:season.getValue()){
    if(episode==null)continue;
    if(found)return episode;
    if(episode.key().equals(current.key()))found=true;
   }
  }
  return null;
 }
}
