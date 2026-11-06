package com.theone.series.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Regression: Google TV must display new rows even with the one-item
 * bootstrap catalog, instead of waiting minutes for entire Xtream feeds. */
public class ProgressiveCatalogLivePreviewTest {
 private static CatalogEntry entry(int id,String title){
  return new CatalogEntry(id,title,"mp4","","","series",id);
 }

 @Test public void streamedCatalogUpdatesExistingBootstrapBeforeFullDownload() throws Exception {
  CatalogEntry bootstrap=entry(10,"EN - Dutton Ranch");
  List<CatalogEntry> first=Arrays.asList(
    entry(11,"ROI Dutton Ranch"),entry(12,"GEI Dutton Ranch"),
    entry(13,"HUI Dutton Ranch"),entry(14,"EN - Dutton Ranch (2026)"));
  List<CatalogEntry> second=Arrays.asList(
    entry(1000000011,"FR Dutton Ranch"),entry(1000000012,"NL Dutton Ranch"),
    entry(1000000013,"DE Dutton Ranch"));
  List<Integer> incremental=Collections.synchronizedList(new ArrayList<>());
  AtomicBoolean allEightBeforeComplete=new AtomicBoolean(false);
  List<ProgressiveCatalog.Source> sources=Arrays.asList(
    preview->{
      preview.accept(first.subList(0,2));
      preview.accept(first);
      return first;
    },
    preview->{
      preview.accept(second);
      return second;
    });
  ProgressiveCatalog.loadStreaming(sources,Collections.singletonList(bootstrap),(rows,complete,successful)->{
    if(!complete){
      incremental.add(rows.size());
      if(rows.size()==8)allEightBeforeComplete.set(true);
    }else{
      assertEquals(2,successful);
      assertEquals(7,rows.size()); // Completed source replaces bootstrap of that provider.
    }
  });
  assertTrue("Streaming must show more than one result despite bootstrap",incremental.stream().anyMatch(x->x>1));
  assertTrue("Second callback must be delivered",incremental.size()>=3);
 }

 @Test public void sourceFailureKeepsCachedTitlesAndAnotherSourcesResults() throws Exception {
  List<CatalogEntry> cached=Arrays.asList(entry(1,"EN Dutton Ranch"),entry(1000000100,"Original"));
  List<ProgressiveCatalog.Source> sources=Arrays.asList(
    preview->{preview.accept(Arrays.asList(entry(2,"ROI Dutton Ranch")));throw new java.io.IOException("Offline midway");},
    preview->Collections.singletonList(entry(1000000110,"New Source Two")));
  List<List<CatalogEntry>> updates=new ArrayList<>();
  ProgressiveCatalog.loadStreaming(sources,cached,(rows,complete,successful)->{
    synchronized(updates){updates.add(new ArrayList<>(rows));}
  });
  assertTrue("Partial preview should be visible without deleting cached results",
      updates.stream().anyMatch(rows->rows.stream().anyMatch(x->x.id==2)));
  assertTrue("Already-cached source one remains available",
      updates.get(updates.size()-1).stream().anyMatch(x->x.id==1));
 }
}
