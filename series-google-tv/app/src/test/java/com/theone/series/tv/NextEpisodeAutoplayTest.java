package com.theone.series.tv;
import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class NextEpisodeAutoplayTest {
 private EpisodeCatalog.Result catalog() throws Exception {
  CatalogEntry series=new CatalogEntry(31,"EN - Sample series","mp4","","","series",0,"123");
  JSONObject episodes=new JSONObject().put("episodes",new JSONObject()
      .put("1",new JSONArray()
         .put(new JSONObject().put("id",101).put("episode_num",1).put("title","Episode 1"))
         .put(new JSONObject().put("id",102).put("episode_num",2).put("title","Episode 2")))
      .put("2",new JSONArray()
         .put(new JSONObject().put("id",201).put("episode_num",1).put("title","Season 2 start"))));
  return EpisodeCatalog.merge(EpisodeCatalog.parse(episodes,series,"",false));
 }
 @Test public void automaticallyAdvancesWithinSeason() throws Exception {
  EpisodeCatalog.Result c=catalog();
  assertEquals(c.seasons.get(1).get(1).key(),NextEpisodeAutoplay.next(c,c.seasons.get(1).get(0)).key());
 }
 @Test public void automaticallyAdvancesIntoNextSeason() throws Exception {
  EpisodeCatalog.Result c=catalog();
  assertEquals(c.seasons.get(2).get(0).key(),NextEpisodeAutoplay.next(c,c.seasons.get(1).get(1)).key());
 }
 @Test public void doesNotAutoPlayAfterLastEpisode() throws Exception {
  EpisodeCatalog.Result c=catalog();
  assertNull(NextEpisodeAutoplay.next(c,c.seasons.get(2).get(0)));
 }
 @Test public void neverAutoPlaysAnotherSeriesOrStandaloneItem() throws Exception {
  EpisodeCatalog.Result c=catalog();
  CatalogEntry other=new CatalogEntry(77,"S01E01 - Unrelated","mp4","","1:1","episode",0,"","episode:other:1:1","en");
  assertNull(NextEpisodeAutoplay.next(c,other));
  assertNull(NextEpisodeAutoplay.next(c,new CatalogEntry(77,"Other series","mp4","","","series",0)));
  assertNull(NextEpisodeAutoplay.next(null,c.seasons.get(1).get(0)));
 }
}
