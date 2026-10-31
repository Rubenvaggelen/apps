package com.theone.series.tv;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public final class EpisodeResponseMemoryTest {
 @Test public void visionQuestEpisodeMetadataLoads() throws Exception {
  String data="{\"episodes\":{\"1\":[{\"id\":417,\"title\":\"VisionQuest E01\",\"episode_num\":1}]}}";
  assertEquals(data,MediaRepository.readEpisodeResponse(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))));
 }
 @Test public void oversizedEpisodeMetadataIsRejectedWithoutParsing() throws Exception {
  byte[] oversized=new byte[12*1000*1000+1];
  try {MediaRepository.readEpisodeResponse(new ByteArrayInputStream(oversized));fail("Must reject oversized series info");}
  catch(IOException expected){assertTrue(expected.getMessage().contains("memory budget"));}
 }
 @Test public void cancelledEpisodeMetadataAborts() throws Exception {
  Thread.currentThread().interrupt();
  try {MediaRepository.readEpisodeResponse(new ByteArrayInputStream(new byte[]{1,2}));fail("Must cancel");}
  catch(IOException expected){assertTrue(expected.getMessage().contains("cancelled"));}
  finally {Thread.interrupted();}
 }
}
