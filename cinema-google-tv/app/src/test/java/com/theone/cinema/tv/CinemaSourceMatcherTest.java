package com.theone.cinema.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class CinemaSourceMatcherTest {
 private static CatalogEntry movie(int id,String title,String tmdb){
  return new CatalogEntry(id,title,"mp4","","","movie",0,tmdb);
 }
 @Test public void alternateProviderWinsWithDifferentTmdbAndMissingYear(){
  CatalogEntry a=movie(3,"EN - Film Example (2026) FHD","111");
  CatalogEntry b=movie(1000000042,"NL - Film Example HD","222");
  Map<String,List<CatalogEntry>> groups=new LinkedHashMap<>();
  groups.put(a.key(),Collections.singletonList(a));
  groups.put(b.key(),Collections.singletonList(b));
  List<CatalogEntry> result=CinemaSourceMatcher.sources(a,groups,"en",1080);
  assertEquals(2,result.size());
  assertEquals(1000000042,result.get(0).id);
  assertEquals(3,result.get(1).id);
 }
 @Test public void conflictingMovieYearsRemainSeparate(){
  CatalogEntry a=movie(3,"EN - Dune (1984)","");
  CatalogEntry b=movie(1000000042,"EN - Dune (2021)","");
  Map<String,List<CatalogEntry>> groups=new LinkedHashMap<>();
  groups.put(b.key(),Collections.singletonList(b));
  List<CatalogEntry> result=CinemaSourceMatcher.sources(a,groups,"en",1080);
  assertEquals(1,result.size());
 }
 @Test public void identicalTmdbDifferentLocalizedTitlesCanFallback(){
  CatalogEntry a=movie(3,"EN - Original Title","567");
  CatalogEntry b=movie(1000000042,"NL - Nederlandstalige Titel","567");
  Map<String,List<CatalogEntry>> groups=new LinkedHashMap<>();
  groups.put(b.key(),Collections.singletonList(b));
  List<CatalogEntry> result=CinemaSourceMatcher.sources(a,groups,"en",1080);
  assertEquals(1000000042,result.get(0).id);
 }
 @Test public void avoidsUnrelatedContentAndLimitsVariants(){
  CatalogEntry a=movie(3,"EN - Example","567");
  CatalogEntry b=movie(1000000042,"EN - Example2","568");
  CatalogEntry adult=new CatalogEntry(1000000043,"EN - Example","mp4","","","xxx",0,"567");
  Map<String,List<CatalogEntry>> groups=new LinkedHashMap<>();
  groups.put(b.key(),Arrays.asList(b,adult));
  List<CatalogEntry> result=CinemaSourceMatcher.sources(a,groups,"en",1080);
  assertEquals(1,result.size());
  assertEquals(a.id,result.get(0).id);
 }
}
