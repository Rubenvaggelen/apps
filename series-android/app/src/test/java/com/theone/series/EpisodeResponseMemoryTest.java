package com.theone.series;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public final class EpisodeResponseMemoryTest {
 @Test public void visionQuestEpisodeMetadataLoads() throws Exception {
  String metadata="{\"episodes\":{\"1\":[{\"id\":417,\"title\":\"VisionQuest E01\",\"episode_num\":1}]}}";
  assertEquals(metadata,MediaRepository.readEpisodeResponse(new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8))));
 }
 @Test public void oversizeDataFailsWithoutParsing() throws Exception {
  byte[] oversized=new byte[8*1024*1024+1];
  try{MediaRepository.readEpisodeResponse(new ByteArrayInputStream(oversized));fail("Must reject oversize series info");}
  catch(IOException expected){assertTrue(expected.getMessage().contains("memory budget"));}
 }
 @Test public void episodeReadCanBeCancelled() throws Exception {
  Thread.currentThread().interrupt();
  try{MediaRepository.readEpisodeResponse(new ByteArrayInputStream(new byte[]{1}));fail("Must cancel");}
  catch(IOException expected){assertTrue(expected.getMessage().contains("cancelled"));}
  finally{Thread.interrupted();}
 }
}
