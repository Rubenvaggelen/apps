package com.theone.mediaplayer;
import org.junit.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class XtreamConfigTest {
 private MockServer one,two;
 private XtreamConfig config;
 private XtreamConfig create(int port)throws Exception { Constructor<XtreamConfig> c=XtreamConfig.class.getDeclaredConstructor(String.class,String.class,String.class);c.setAccessible(true);return c.newInstance("http://127.0.0.1:"+port,"fixture-user","fixture-password"); }
 private static class MockServer {
  final ServerSocket socket;
  MockServer()throws Exception {
   socket=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
   Thread thread=new Thread(()->{while(!socket.isClosed()) {
    try(Socket client=socket.accept()) {
     client.setSoTimeout(3000);
     BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
     String request=in.readLine(),line;
     while((line=in.readLine())!=null&&!line.isEmpty()){}
     String raw=request.contains("get_series_info")?"{\"episodes\":{\"1\":[{\"id\":7,\"title\":\"Episode\"}]}}":request.contains("categories")?"[{\"category_id\":1,\"category_name\":\"Drama\"}]":"[{\"stream_id\":7,\"name\":\"Film\",\"category_id\":1}]";
     byte[] body=raw.getBytes(StandardCharsets.UTF_8);
     OutputStream out=client.getOutputStream();
     out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(body);out.flush();
    }catch(Exception ignored){}
   }});thread.setDaemon(true);thread.start();
  }
  int port(){return socket.getLocalPort();}
  void stop(int unused){try{socket.close();}catch(Exception ignored){}}
 }
 private MockServer serve(boolean secondary)throws Exception {return new MockServer();}
 @Before public void setup()throws Exception {one=serve(false);two=serve(true);config=create(one.port());Field f=XtreamConfig.class.getDeclaredField("secondary");f.setAccessible(true);f.set(config,create(two.port()));}
 @After public void stop(){one.stop(0);two.stop(0);}
 @Test public void sameProviderIdsRemainSeparate()throws Exception {JSONArray a=new JSONArray(config.fetchCatalog(config.api("get_vod_streams")));assertEquals(2,a.length());assertEquals("7",a.getJSONObject(0).optString("stream_id"));assertEquals("source2:7",a.getJSONObject(1).optString("stream_id"));assertTrue(config.stream("movie","source2:7","mp4").contains(":"+two.port()+"/movie/"));}
 @Test public void secondaryCategoryRoutesOnlyToSecondary()throws Exception {JSONArray a=new JSONArray(config.fetchCatalog(config.api("get_vod_streams","category_id","source2:1")));assertEquals(1,a.length());assertEquals("source2:7",a.getJSONObject(0).optString("stream_id"));}
 @Test public void seriesEpisodesKeepTheirSource()throws Exception {JSONObject o=new JSONObject(config.fetchCatalog(config.api("get_series_info","series_id","source2:3")));assertEquals("source2:7",o.getJSONObject("episodes").getJSONArray("1").getJSONObject(0).getString("id"));assertTrue(config.stream("series","source2:7","mkv").endsWith("/series/fixture-user/fixture-password/7.mkv"));}
 @Test public void degradedCatalogIsReportedWhenProviderFails()throws Exception {
  two.stop(0);
  JSONArray rows=new JSONArray(config.fetchCatalog(config.api("get_live_streams")));
  assertEquals(1,rows.length());
  assertTrue("Do not silently present the one-provider list as complete",config.isLastLiveCatalogPartial());
 }
 @Test public void suspiciousProviderDropKeepsPreviouslyKnownDutchChannels()throws Exception {
  JSONArray before=new JSONArray();
  for(int i=0;i<80;i++)before.put(new JSONObject().put("stream_id",i+1).put("name","NL - Channel "+i));
  JSONArray after=new JSONArray().put(new JSONObject().put("stream_id",1).put("name","NL - VIAPLAY DARTS PPV"));
  assertTrue(XtreamConfig.suspiciousCatalogShrink(before,after,true));
  assertFalse(XtreamConfig.suspiciousCatalogShrink(before,before,true));
  assertFalse(XtreamConfig.suspiciousCatalogShrink(new JSONArray(),after,true));
 }
 @Test public void losingMostProviderEntriesDoesNotReplaceCache()throws Exception {
  JSONArray original=new JSONArray();
  for(int i=0;i<200;i++)original.put(new JSONObject().put("stream_id",i+1).put("name","NL - "+i));
  JSONArray partial=new JSONArray().put(new JSONObject().put("stream_id",1).put("name","NL - only"));
  assertTrue(XtreamConfig.suspiciousCatalogShrink(original,partial,false));
 }
 @Test public void primarySurvivesUnavailableSecondary()throws Exception {two.stop(0);JSONArray a=new JSONArray(config.fetchCatalog(config.api("get_live_streams")));assertEquals(1,a.length());assertEquals("7",a.getJSONObject(0).optString("stream_id"));}

 @Test public void seriesCatalogUsesCompleteSnapshotWhenProvidersGoOffline()throws Exception {
  java.io.File dir=java.nio.file.Files.createTempDirectory("theone-series-catalog-test").toFile();
  Field cacheField=XtreamConfig.class.getDeclaredField("catalogCacheDir");
  cacheField.setAccessible(true);cacheField.set(config,dir);
  try {
   JSONArray available=new JSONArray(config.fetchCatalog(config.api("get_series")));
   assertEquals(2,available.length());
   assertEquals("source2:7",available.getJSONObject(1).getString("stream_id"));
   one.stop(0);two.stop(0);
   JSONArray offline=new JSONArray(config.fetchCatalog(config.api("get_series")));
   assertEquals(2,offline.length());
   assertEquals("source2:7",offline.getJSONObject(1).getString("stream_id"));
  } finally {
   java.io.File[] contents=dir.listFiles();
   if(contents!=null)for(java.io.File f:contents)f.delete();
   dir.delete();
  }
 }
 @Test public void seriesCategoryCacheRecoversWhenSecondarySourceDisconnects()throws Exception {
  java.io.File dir=java.nio.file.Files.createTempDirectory("theone-series-category-test").toFile();
  Field cacheField=XtreamConfig.class.getDeclaredField("catalogCacheDir");
  cacheField.setAccessible(true);cacheField.set(config,dir);
  try {
   String query=config.api("get_series","category_id","source2:1");
   JSONArray first=new JSONArray(config.fetchCatalog(query));
   assertEquals(1,first.length());
   assertEquals("source2:7",first.getJSONObject(0).getString("stream_id"));
   two.stop(0);
   JSONArray recovered=new JSONArray(config.fetchCatalog(query));
   assertEquals("source2:7",recovered.getJSONObject(0).getString("stream_id"));
   java.io.File[] files=dir.listFiles();
   assertNotNull(files);
   assertTrue(files.length>0);
   for(java.io.File file:files) {
    assertFalse(file.getName().contains("fixture-password"));
    assertFalse(file.getName().contains("fixture-user"));
   }
  }finally{
   java.io.File[] contents=dir.listFiles();
   if(contents!=null)for(java.io.File f:contents)f.delete();
   dir.delete();
  }
 }

 @Test public void progressiveSeriesCatalogStreamsEachProviderInBatches()throws Exception {
  java.util.List<String> ids=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
  boolean complete=config.streamSeriesCatalog(1,(rows,second)->{
   for(int i=0;i<rows.length();i++) {
    org.json.JSONObject row=rows.optJSONObject(i);
    if(row!=null)ids.add(row.optString("stream_id"));
   }
  });
  assertTrue(complete);
  assertEquals(2,ids.size());
  assertTrue(ids.contains("7"));
  assertTrue(ids.contains("source2:7"));
 }
 @Test public void progressiveSeriesLoadsPreviousSnapshotOffline()throws Exception {
  java.io.File dir=java.nio.file.Files.createTempDirectory("theone-batched-series").toFile();
  Field cacheField=XtreamConfig.class.getDeclaredField("catalogCacheDir");
  cacheField.setAccessible(true);cacheField.set(config,dir);
  try{
   java.util.concurrent.atomic.AtomicInteger initial=new java.util.concurrent.atomic.AtomicInteger();
   assertTrue(config.streamSeriesCatalog(50,(batch,second)->initial.addAndGet(batch.length())));
   assertEquals(2,initial.get());
   one.stop(0);two.stop(0);
   java.util.concurrent.atomic.AtomicInteger recovered=new java.util.concurrent.atomic.AtomicInteger();
   assertTrue(config.streamSeriesCatalog(50,(batch,second)->recovered.addAndGet(batch.length())));
   assertEquals(2,recovered.get());
  }finally{
   java.io.File[] files=dir.listFiles();
   if(files!=null)for(java.io.File f:files)f.delete();
   dir.delete();
  }
 }
}
