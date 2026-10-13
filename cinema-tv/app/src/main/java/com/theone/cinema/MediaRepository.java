package com.theone.cinema;
import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.*;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
final class MediaRepository {
 private final String server,token;private JSONObject secondary;private volatile int lastSuccessful=2;private final android.util.LruCache<String,String> posterUrls=new android.util.LruCache<>(512);
 MediaRepository(Context c)throws Exception{
  try(InputStream in=c.getAssets().open("private_cinema.json")){JSONObject x=new JSONObject(read(in));server=x.getString("relay_url");token=x.getString("relay_token");}
  if(!server.startsWith("https://rubenvanaggelen.com/")||token.isEmpty())throw new IOException("Source unavailable");
  try(InputStream in=c.getAssets().open("private_xtream_secondary.json")){secondary=new JSONObject(read(in));}catch(Exception ignored){}
 }
 private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){throw new IllegalArgumentException();}}
 private static String read(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>100*1024*1024)throw new IOException("Catalog exceeds limit");}return out.toString("UTF-8");}
 private String secondaryApi(String action)throws Exception{return secondary.getString("server")+"/player_api.php?username="+enc(secondary.getString("username"))+"&password="+enc(secondary.getString("password"))+"&action="+action;}
 private Set<String> adultCategories()throws Exception {
  Set<String> ids=new HashSet<>();HttpURLConnection c=(HttpURLConnection)new URL(secondaryApi("get_vod_categories")).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);
  try{if(c.getResponseCode()!=200)return ids;JSONArray a=new JSONArray(read(c.getInputStream()));for(int i=0;i<a.length();i++){JSONObject row=a.optJSONObject(i);if(row!=null&&CatalogEntry.adultCategory(row.optString("category_name")))ids.add(row.optString("category_id"));}}finally{c.disconnect();}return ids;
 }
 List<CatalogEntry> load(String type)throws Exception {
  List<CatalogEntry> list=new ArrayList<>();int successful=0;
  try{list.addAll(loadSource(type,server+"?action=catalog&type=movies",false,Collections.emptySet()));successful++;}catch(Exception ignored){}
  if(secondary!=null){Set<String> adultIds=Collections.emptySet();try{adultIds=adultCategories();}catch(Exception ignored){}try{list.addAll(loadSource(type,secondaryApi("get_vod_streams"),true,adultIds));successful++;}catch(Exception ignored){}}
  lastSuccessful=successful;if(successful==0)throw new IOException("Catalog unavailable");list.sort((a,b)->Long.compare(b.added,a.added));return list;
 }
 private List<CatalogEntry> loadSource(String type,String url,boolean second,Set<String> adultIds)throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(90000);if(!second)c.setRequestProperty("X-Cinema-Token",token);List<CatalogEntry> list=new ArrayList<>();
  try{if(c.getResponseCode()!=200)throw new IOException("Catalog unavailable");try(JsonReader reader=new JsonReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){reader.beginArray();while(reader.hasNext()){
   if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");int id=0,adult=0;String title="",extension="mp4",poster="",category="",movieId="";long added=0;reader.beginObject();while(reader.hasNext()){
    String key=reader.nextName();if(reader.peek()==JsonToken.NULL){reader.nextNull();continue;}switch(key){case "stream_id":id=(int)number(reader);break;case "name":title=reader.nextString();break;case "container_extension":extension=reader.nextString();break;case "stream_icon":case "cover":case "movie_image":case "poster":String candidate=reader.nextString();if(poster.isEmpty()&&!candidate.isEmpty()&&!candidate.equalsIgnoreCase("null"))poster=candidate;break;case "category_id":category=reader.nextString();break;case "is_adult":adult=reader.peek()==JsonToken.BOOLEAN?(reader.nextBoolean()?1:0):(int)number(reader);break;case "tmdb_id":case "tmdb":String identifier=reader.nextString();if(movieId.isEmpty()&&identifier.matches("[1-9][0-9]{0,9}"))movieId=identifier;break;case "added":added=number(reader);break;default:reader.skipValue();}}
   reader.endObject();if(id<=0||id>=1000000000||title.isEmpty())continue;
   boolean xxx=adult==1||adultIds.contains(category)||CatalogEntry.adultTitle(title);
   if(xxx!=type.equals("xxx"))continue;
   if(second){id+=1000000000;if(poster.startsWith("/"))poster=secondary.getString("server")+poster;}
   list.add(new CatalogEntry(id,title,extension,poster,category,xxx?"xxx":"movies",added,movieId));
  }reader.endArray();}}finally{c.disconnect();}return list;
 }
 String poster(CatalogEntry entry)throws Exception {
  String key=entry.type+":"+entry.id;String cached=posterUrls.get(key);if(cached!=null)return cached;
  boolean second=entry.id>=1000000000;String url;
  if(second){if(secondary==null)return "";url=secondaryApi("get_vod_info")+"&vod_id="+(entry.id-1000000000);}else url=server+"?action=poster&id="+entry.id;
  HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(7000);connection.setReadTimeout(15000);if(!second)connection.setRequestProperty("X-Cinema-Token",token);
  String result="";try{if(connection.getResponseCode()==200){JSONObject data=new JSONObject(read(connection.getInputStream()));result=second?PosterMetadata.choose(data,secondary.getString("server")):PosterMetadata.resolve(data.optString("poster"),server);}}finally{connection.disconnect();}
  posterUrls.put(key,result);return result;
 }
 void clearPosterCache(){posterUrls.evictAll();}
 private static long number(JsonReader r)throws Exception{try{return Long.parseLong(r.nextString());}catch(NumberFormatException e){return 0;}}
 boolean partialSources(){return secondary!=null&&lastSuccessful<2;}
 Map<String,String> streamHeaders(){return Collections.singletonMap("X-Cinema-Token",token);}
 Map<String,String> headersForUri(String uri){return uri.startsWith(server+"?")?streamHeaders():Collections.emptyMap();}
 String stream(CatalogEntry x){
  if(x.id>=1000000000&&secondary!=null){try{return secondary.getString("server")+"/movie/"+enc(secondary.getString("username"))+"/"+enc(secondary.getString("password"))+"/"+(x.id-1000000000)+"."+x.extension;}catch(Exception e){throw new IllegalStateException("Source unavailable");}}
  return server+"?action=stream&id="+x.id+"&ext="+x.extension+"&type=movies";
 }
}
