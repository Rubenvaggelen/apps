package com.theone.series.tv;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SeriesSourceMatcherTest {
 private CatalogEntry show(int id,String title,String movieId){
  return new CatalogEntry(id,title,"mp4","","","series",0,movieId);
 }
 @Test public void findMobLandFromTwoProvidersEvenWithDifferentIdsAndTitleSpacing(){
  CatalogEntry primary=show(14,"EN - Mob Land 1080p","123");
  CatalogEntry secondary=show(1000000021,"EN - MobLand FHD","456");
  Map<String,List<CatalogEntry>> catalog=new LinkedHashMap<>();
  catalog.put(primary.key(),Collections.singletonList(primary));
  catalog.put(secondary.key(),Collections.singletonList(secondary));
  List<CatalogEntry> options=SeriesSourceMatcher.sources(primary,catalog);
  assertEquals(2,options.size());
  assertEquals(14,options.get(0).id);
  assertEquals(1000000021,options.get(1).id);
 }
 @Test public void unrelatedShowsAndEpisodesAreNeverSelected(){
  CatalogEntry selected=show(14,"EN - Mob Land","123");
  CatalogEntry other=show(1000000031,"EN - Mobb Land","789");
  CatalogEntry episode=new CatalogEntry(25,"S01E03", "mkv","","1:3","episode",0);
  Map<String,List<CatalogEntry>> catalog=new LinkedHashMap<>();
  catalog.put(selected.key(),Collections.singletonList(selected));
  catalog.put(other.key(),Collections.singletonList(other));
  catalog.put(episode.key(),Collections.singletonList(episode));
  List<CatalogEntry> options=SeriesSourceMatcher.sources(selected,catalog);
  assertEquals(1,options.size());
  assertEquals(selected.id,options.get(0).id);
 }
 @Test public void noDuplicateProviders(){
  CatalogEntry selected=show(14,"Mob Land","123");
  Map<String,List<CatalogEntry>> catalog=new LinkedHashMap<>();
  catalog.put(selected.key(),Arrays.asList(selected,selected));
  assertEquals(1,SeriesSourceMatcher.sources(selected,catalog).size());
 }
 @Test public void largeCatalogOnlyLoadsOneBackupProviderForPeakStick(){
  CatalogEntry primary=show(41,"EN - VisionQuest FHD","321");
  CatalogEntry secondary=show(1000000044,"EN - Vision Quest HD","654");
  Map<String,List<CatalogEntry>> entries=new LinkedHashMap<>();
  entries.put(primary.key(),Collections.singletonList(primary));
  for(int i=0;i<8000;i++){
   CatalogEntry duplicate=show(1000+i,"EN - VisionQuest UHD",""+(i+2));
   entries.put("duplicate-"+i,Collections.singletonList(duplicate));
  }
  entries.put(secondary.key(),Collections.singletonList(secondary));
  List<CatalogEntry> sources=SeriesSourceMatcher.sources(primary,entries);
  assertEquals(2,sources.size());
  assertEquals(41,sources.get(0).id);
  assertEquals(1000000044,sources.get(1).id);
 }

}
