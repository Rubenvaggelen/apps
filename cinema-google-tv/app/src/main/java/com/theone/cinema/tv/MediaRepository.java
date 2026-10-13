package com.theone.cinema.tv;
import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.*;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
final class MediaRepository {
 private final File cacheDir;private final String server,token;private JSONObject secondary;private volatile int lastSuccessful=2;private final android.util.LruCache<String,List<String>> posterUrls=new android.util.LruCache<>(512);
 MediaRepository(Context c)throws Exception{cacheDir=new File(c.getFilesDir(),"catalogs");cacheDir.mkdirs();
  try(InputStream in=c.getAssets().open("private_cinema.json")){JSONObject x=new JSONObject(read(in));server=x.getString("relay_url");token=x.getString("relay_token");}
  if(!server.startsWith("https://rubenvanaggelen.com/")||token.isEmpty())throw new IOException("Source unavailable");
  try(InputStream in=c.getAssets().open("private_xtream_secondary.json")){secondary=new JSONObject(read(in));}catch(Exception ignored){}
 }
 private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){throw new IllegalArgumentException();}}
 private static String read(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>100*1024*1024)throw new IOException("Catalog exceeds limit");}return out.toString("UTF-8");}
 private String secondaryApi(String action)throws Exception{return secondary.getString("server")+"/player_api.php?username="+enc(secondary.getString("username"))+"&password="+enc(secondary.getString("password"))+"&action="+action;}
 private Set<String> adultCategories()throws Exception {
  Set<String> ids=new HashSet<>();HttpURLConnection c=(HttpURLConnection)new URL(secondaryApi("get_vod_categories")).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);
  try{if(c.getResponseCode()!=200)throw new IOException("Categories unavailable");JSONArray a=new JSONArray(read(c.getInputStream()));for(int i=0;i<a.length();i++){JSONObject row=a.optJSONObject(i);if(row!=null&&CatalogEntry.adultCategory(row.optString("category_name")))ids.add(row.optString("category_id"));}}finally{c.disconnect();}return ids;
 }
 private android.util.AtomicFile cacheFile(String type){return new android.util.AtomicFile(new File(cacheDir,type.equals("xxx")?"xxx-v1.bin":"movies-v1.bin"));}
 private List<CatalogEntry> cached(String type){try(InputStream in=cacheFile(type).openRead()){return CatalogCodec.read(in,type);}catch(Exception ignored){return Collections.emptyList();}}
 private void save(String type,List<CatalogEntry> entries){android.util.AtomicFile file=cacheFile(type);FileOutputStream out=null;try{out=file.startWrite();CatalogCodec.write(out,entries);file.finishWrite(out);}catch(Exception ignored){if(out!=null)file.failWrite(out);}}
 void load(String type,ProgressiveCatalog.Listener listener)throws Exception {
  List<java.util.concurrent.Callable<List<CatalogEntry>>> sources=new ArrayList<>();
  sources.add(()->loadSource(type,server+"?action=catalog&type=movies",false,Collections.emptySet()));
  if(secondary!=null)sources.add(()->{Set<String> adultIds;try{adultIds=adultCategories();}catch(Exception e){throw new IOException("Categories unavailable");}return loadSource(type,secondaryApi("get_vod_streams"),true,adultIds);});
  ProgressiveCatalog.load(sources,cached(type),(entries,complete,successful)->{lastSuccessful=successful;listener.update(entries,complete,successful);if(successful>0)save(type,entries);});
 }
 private List<CatalogEntry> loadSource(String type,String url,boolean second,Set<String> adultIds)throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(45000);if(!second)c.setRequestProperty("X-Cinema-Token",token);List<CatalogEntry> list=new ArrayList<>();
  try{if(c.getResponseCode()!=200)throw new IOException("Catalog unavailable");try(JsonReader reader=new JsonReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){reader.beginArray();while(reader.hasNext()){
   if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");int id=0,adult=0;String title="",extension="mp4",poster="",category="",movieId="";long added=0;reader.beginObject();while(reader.hasNext()){
    String key=reader.nextName();if(reader.peek()==JsonToken.NULL){reader.nextNull();continue;}switch(key){case "stream_id":id=(int)number(reader);break;case "name":title=reader.nextString();break;case "container_extension":extension=reader.nextString();break;case "stream_icon":case "cover":case "movie_image":case "poster":String candidate=reader.nextString();if(poster.isEmpty()&&!candidate.isEmpty()&&!candidate.equalsIgnoreCase("null"))poster=candidate;break;case "category_id":category=reader.nextString();break;case "is_adult":adult=reader.peek()==JsonToken.BOOLEAN?(reader.nextBoolean()?1:0):(int)number(reader);break;case "tmdb_id":case "tmdb":String identifier=reader.nextString();if(movieId.isEmpty()&&validIdentifier(identifier))movieId=identifier;break;case "added":added=number(reader);break;default:reader.skipValue();}}
   reader.endObject();if(id<=0||id>=1000000000||title.isEmpty())continue;
   boolean xxx=adult==1||adultIds.contains(category)||CatalogEntry.adultTitle(title);
   if(xxx!=type.equals("xxx"))continue;
   if(second){id+=1000000000;poster=PosterMetadata.resolve(poster,secondary.getString("server"));}
   list.add(new CatalogEntry(id,title,extension,poster,category,xxx?"xxx":"movies",added,movieId));
  }reader.endArray();}}finally{c.disconnect();}return list;
 }
 List<String> posters(CatalogEntry entry)throws Exception {
  String key=entry.type+":"+entry.id;List<String> cached=posterUrls.get(key);if(cached!=null)return cached;
  boolean second=entry.id>=1000000000;String url;
  if(second){if(secondary==null)return Collections.emptyList();url=secondaryApi("get_vod_info")+"&vod_id="+(entry.id-1000000000);}else url=server+"?action=poster&id="+entry.id;
  HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(7000);connection.setReadTimeout(15000);if(!second)connection.setRequestProperty("X-Cinema-Token",token);
  List<String> result=Collections.emptyList();try{if(connection.getResponseCode()==200){JSONObject data=new JSONObject(read(connection.getInputStream()));result=PosterMetadata.candidates(data,second?secondary.getString("server"):server);}}finally{connection.disconnect();}
  posterUrls.put(key,result);return result;
 }
 void clearPosterCache(){posterUrls.evictAll();}
 private static boolean validIdentifier(String s){if(s.length()==0||s.length()>10||s.charAt(0)<'1'||s.charAt(0)>'9')return false;for(int i=1;i<s.length();i++)if(s.charAt(i)<'0'||s.charAt(i)>'9')return false;return true;}
 private static long number(JsonReader r)throws Exception{try{return Long.parseLong(r.nextString());}catch(NumberFormatException e){return 0;}}
 boolean partialSources(){return secondary!=null&&lastSuccessful<2;}
 Map<String,String> streamHeaders(){return Collections.singletonMap("X-Cinema-Token",token);}
 Map<String,String> headersForUri(String uri){return uri.startsWith(server+"?")?streamHeaders():Collections.emptyMap();}
 String stream(CatalogEntry x){
  if(x.id>=1000000000&&secondary!=null){try{return secondary.getString("server")+"/movie/"+enc(secondary.getString("username"))+"/"+enc(secondary.getString("password"))+"/"+(x.id-1000000000)+"."+x.extension;}catch(Exception e){throw new IllegalStateException("Source unavailable");}}
  return server+"?action=stream&id="+x.id+"&ext="+x.extension+"&type=movies";
 }
}
