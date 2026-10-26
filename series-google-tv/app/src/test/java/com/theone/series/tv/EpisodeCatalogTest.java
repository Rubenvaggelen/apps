package com.theone.series.tv;
import org.json.*;import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class EpisodeCatalogTest {
 private CatalogEntry parent(int id,String language){return new CatalogEntry(id,language+" - Example 1080p","mp4","https://example.test/cover.jpg","","series",0,"123");}
 private JSONObject data(int id,String title,int number)throws Exception{return new JSONObject().put("episodes",new JSONObject().put("1",new JSONArray().put(new JSONObject().put("id",id).put("title",title).put("episode_num",number).put("container_extension","mkv"))));}
 @Test public void episodeLanguagesFromBothSourcesMergeEnglishFirst()throws Exception{List<CatalogEntry> all=new ArrayList<>();all.addAll(EpisodeCatalog.parse(data(3,"Nederlandse aflevering",1),parent(2,"NL"),"https://provider.test",true));all.addAll(EpisodeCatalog.parse(data(5,"English episode",1),parent(1,"EN"),"https://provider.test",false));EpisodeCatalog.Result r=EpisodeCatalog.merge(all);assertEquals(1,r.seasons.get(1).size());assertEquals(5,r.seasons.get(1).get(0).id);assertEquals(2,r.variants.values().iterator().next().size());}
 @Test public void episodeNumbersRemainInOrder()throws Exception{List<CatalogEntry> all=new ArrayList<>();all.addAll(EpisodeCatalog.parse(data(2,"Second",2),parent(1,"EN"),"",false));all.addAll(EpisodeCatalog.parse(data(1,"First",1),parent(1,"EN"),"",false));assertEquals(1,EpisodeCatalog.merge(all).seasons.get(1).get(0).id);}
 @Test public void missingEnglishEpisodeStillExists()throws Exception{EpisodeCatalog.Result r=EpisodeCatalog.merge(EpisodeCatalog.parse(data(1,"Backup",1),parent(1000000001,"NL"),"",true));assertEquals(1000000001,r.seasons.get(1).get(0).id);}
 @Test public void identicalNamesAcrossSeasonsRemainSeparate()throws Exception{JSONObject d=data(1,"Title",1);d.getJSONObject("episodes").put("2",new JSONArray().put(new JSONObject().put("id",2).put("title","Title").put("episode_num",1)));EpisodeCatalog.Result r=EpisodeCatalog.merge(EpisodeCatalog.parse(d,parent(1,"EN"),"",false));assertEquals(2,r.seasons.size());assertEquals(2,r.variants.size());}
 @Test public void invalidEpisodesAreSkippedAndPosterUsesSeriesCover()throws Exception{JSONObject d=data(-2,"Invalid",1);assertTrue(EpisodeCatalog.parse(d,parent(1,"EN"),"",false).isEmpty());CatalogEntry e=EpisodeCatalog.parse(data(2,"Valid",1),parent(1,"EN"),"",false).get(0);assertEquals("https://example.test/cover.jpg",e.poster);assertEquals("mkv",e.extension);}
 @Test public void missingEpisodeTitleHasReadableLabel()throws Exception{CatalogEntry e=EpisodeCatalog.parse(data(2,"",1),parent(1,"EN"),"",false).get(0);assertTrue(e.title.contains("S01E01"));assertTrue(e.title.contains("Aflevering 1"));}
 @Test public void titleEpisodeNumbersPreventDuplicateMetadataFromHidingEpisodes()throws Exception{JSONObject d=data(1,"Example S01E01",1);d.getJSONObject("episodes").getJSONArray("1").put(new JSONObject().put("id",2).put("title","Example S01E02").put("episode_num",1)).put(new JSONObject().put("id",3).put("title","Example S01E03").put("episode_num",1));EpisodeCatalog.Result result=EpisodeCatalog.merge(EpisodeCatalog.parse(d,parent(1,"EN"),"",false));assertEquals(3,result.seasons.get(1).size());}
 @Test public void mobLandS1E3FindsBothSourceAlternativesDespiteDifferentTmdbIds()throws Exception{
   CatalogEntry first=new CatalogEntry(51,"EN - Mob Land 1080p","mp4","","","series",0,"123");
   CatalogEntry second=new CatalogEntry(1000000052,"EN - MobLand FHD","mp4","","","series",0,"456");
   JSONObject episode1=data(301,"Mob Land S01E03",3);
   JSONObject episode2=data(302,"MobLand S01E03",3);
   List<CatalogEntry> combined=new ArrayList<>();
   combined.addAll(EpisodeCatalog.parse(episode1,first,"",false));
   combined.addAll(EpisodeCatalog.parse(episode2,second,"",true));
   EpisodeCatalog.Result result=EpisodeCatalog.merge(combined);
   assertEquals("Episode appears once in season 1",1,result.seasons.get(1).size());
   List<CatalogEntry> alternatives=result.variants.get(result.seasons.get(1).get(0).key());
   assertNotNull(alternatives);
   assertEquals("Both providers remain selectable",2,alternatives.size());
   assertTrue(alternatives.stream().anyMatch(e->e.id==301));
   assertTrue(alternatives.stream().anyMatch(e->e.id==1000000302));
 }
 @Test public void sameSeriesDifferentSeasonsStillSeparateAcrossProviders()throws Exception{
   CatalogEntry first=new CatalogEntry(51,"EN - Mob Land","mp4","","","series",0,"123");
   CatalogEntry second=new CatalogEntry(1000000052,"EN - MobLand","mp4","","","series",0,"456");
   JSONObject s1=data(301,"Episode 3",3);
   JSONObject s2=new JSONObject().put("episodes",new JSONObject().put("2",
       new JSONArray().put(new JSONObject().put("id",302).put("episode_num",3).put("title","Episode 3"))));
   List<CatalogEntry> combined=new ArrayList<>();
   combined.addAll(EpisodeCatalog.parse(s1,first,"",false));
   combined.addAll(EpisodeCatalog.parse(s2,second,"",true));
   EpisodeCatalog.Result result=EpisodeCatalog.merge(combined);
   assertEquals(2,result.seasons.size());
   assertEquals(2,result.variants.size());
 }
 @Test public void sourceWithOneEpisodeDoesNotInventOtherEpisodes()throws Exception{assertEquals(1,EpisodeCatalog.merge(EpisodeCatalog.parse(data(1,"Episode S01E01",1),parent(1,"EN"),"",false)).seasons.get(1).size());}
}
