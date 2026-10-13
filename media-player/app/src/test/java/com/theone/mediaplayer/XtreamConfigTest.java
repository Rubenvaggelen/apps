package com.theone.mediaplayer;
import org.junit.*;
import org.json.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class XtreamConfigTest {
 private HttpServer one,two;
 private XtreamConfig config;
 private XtreamConfig create(int port)throws Exception { Constructor<XtreamConfig> c=XtreamConfig.class.getDeclaredConstructor(String.class,String.class,String.class);c.setAccessible(true);return c.newInstance("http://127.0.0.1:"+port,"fixture-user","fixture-password"); }
 private HttpServer serve(boolean secondary)throws Exception {
  HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  s.createContext("/player_api.php",e->{String q=e.getRequestURI().getQuery();String raw=q.contains("get_series_info")?"{\"episodes\":{\"1\":[{\"id\":7,\"title\":\"Episode\"}]}}":q.contains("categories")?"[{\"category_id\":1,\"category_name\":\"Drama\"}]":"[{\"stream_id\":7,\"name\":\"Film\",\"category_id\":1}]";byte[] b=raw.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});s.start();return s;
 }
 @Before public void setup()throws Exception {one=serve(false);two=serve(true);config=create(one.getAddress().getPort());Field f=XtreamConfig.class.getDeclaredField("secondary");f.setAccessible(true);f.set(config,create(two.getAddress().getPort()));}
 @After public void stop(){one.stop(0);two.stop(0);}
 @Test public void sameProviderIdsRemainSeparate()throws Exception {JSONArray a=new JSONArray(config.fetchCatalog(config.api("get_vod_streams")));assertEquals(2,a.length());assertEquals("7",a.getJSONObject(0).optString("stream_id"));assertEquals("source2:7",a.getJSONObject(1).optString("stream_id"));assertTrue(config.stream("movie","source2:7","mp4").contains(":"+two.getAddress().getPort()+"/movie/"));}
 @Test public void secondaryCategoryRoutesOnlyToSecondary()throws Exception {JSONArray a=new JSONArray(config.fetchCatalog(config.api("get_vod_streams","category_id","source2:1")));assertEquals(1,a.length());assertEquals("source2:7",a.getJSONObject(0).optString("stream_id"));}
 @Test public void seriesEpisodesKeepTheirSource()throws Exception {JSONObject o=new JSONObject(config.fetchCatalog(config.api("get_series_info","series_id","source2:3")));assertEquals("source2:7",o.getJSONObject("episodes").getJSONArray("1").getJSONObject(0).getString("id"));assertTrue(config.stream("series","source2:7","mkv").endsWith("/series/fixture-user/fixture-password/7.mkv"));}
 @Test public void primarySurvivesUnavailableSecondary()throws Exception {two.stop(0);JSONArray a=new JSONArray(config.fetchCatalog(config.api("get_live_streams")));assertEquals(1,a.length());assertEquals("7",a.getJSONObject(0).optString("stream_id"));}
}
