package com.theone.series;
import org.junit.Test;import java.util.*;import static org.junit.Assert.*;
public class SeriesSourceMatcherTest {
 @Test public void visionQuestFromBothProvidersIncludesBothSources() {
  CatalogEntry first=new CatalogEntry(101,"EN - VisionQuest FHD","mp4","","","series",0,"123");
  CatalogEntry second=new CatalogEntry(1000000102,"EN - Vision Quest HD","mp4","","","series",0,"456");
  Map<String,List<CatalogEntry>> variants=new HashMap<>();
  variants.put(first.key(),Collections.singletonList(first));variants.put(second.key(),Collections.singletonList(second));
  List<CatalogEntry> sources=SeriesSourceMatcher.sources(first,variants);
  assertEquals(2,sources.size());
  assertTrue(sources.stream().anyMatch(e->e.id==first.id));
  assertTrue(sources.stream().anyMatch(e->e.id==second.id));
 }
 @Test public void otherShowsDoNotMixIn() {
  CatalogEntry first=new CatalogEntry(101,"VisionQuest","mp4","","","series",0);
  CatalogEntry unrelated=new CatalogEntry(102,"Vision Quest 2","mp4","","","series",0);
  Map<String,List<CatalogEntry>> groups=new HashMap<>();groups.put("other",Collections.singletonList(unrelated));
  assertEquals(1,SeriesSourceMatcher.sources(first,groups).size());
 }
}
