package com.theone.tv.phone;
import android.content.Context;import android.util.*;import org.json.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
final class MediaRepository {
 private final File dir;private final JSONObject primary,secondary;private volatile int successful=2;
 MediaRepository(Context c)throws Exception{dir=new File(c.getFilesDir(),"catalogs");dir.mkdirs();primary=config(c,"private_xtream_primary.json");secondary=config(c,"private_xtream_secondary.json");}
 private JSONObject config(Context c,String name)throws Exception{try(InputStream in=c.getAssets().open(name)){JSONObject value=new JSONObject(read(in));for(String key:new String[]{"server","username","password"})if(value.optString(key).isEmpty())throw new IOException("Source unavailable");return value;}}
 private static String read(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>8*1024*1024)throw new IOException("Response exceeds limit");}return out.toString("UTF-8");}
 private static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}
 private String api(JSONObject source,String action)throws Exception{return source.getString("server")+"/player_api.php?username="+enc(source.getString("username"))+"&password="+enc(source.getString("password"))+"&action="+action;}
 private HttpURLConnection connect(String url)throws Exception{return connect(url,8000,15000);}
 private HttpURLConnection connect(String url,int timeout,int readTimeout)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(timeout);c.setReadTimeout(readTimeout);return c;}
 private static final long CATEGORY_TTL_MS=6L*60L*60L*1000L;
 private AtomicFile categoryCache(boolean second){return new AtomicFile(new File(dir,second?"secondary-categories-v1.json":"primary-categories-v1.json"));}
 private Map<String,String> categories(JSONObject source,boolean second,boolean forceRefresh){
  AtomicFile store=categoryCache(second);
  Map<String,String> previous=new HashMap<>();
  try(InputStream in=store.openRead()){JSONObject saved=new JSONObject(read(in));Iterator<String> ids=saved.keys();while(ids.hasNext()){String id=ids.next();previous.put(id,saved.optString(id));}}catch(Exception ignored){}
  long age=System.currentTimeMillis()-store.getBaseFile().lastModified();
  if(!forceRefresh&&!previous.isEmpty()&&age>=0&&age<CATEGORY_TTL_MS)return previous;
  HttpURLConnection c=null;
  try{
   c=connect(api(source,"get_live_categories"),3500,5000);
   if(c.getResponseCode()!=200)return previous;
   JSONArray rows=new JSONArray(read(c.getInputStream()));Map<String,String> fresh=new HashMap<>();
   for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null){String id=row.optString("category_id"),name=row.optString("category_name");if(!id.isEmpty()&&!name.isEmpty())fresh.put(id,name);}}
   if(fresh.isEmpty())return previous;
   FileOutputStream out=null;
   try{out=store.startWrite();out.write(new JSONObject(fresh).toString().getBytes(StandardCharsets.UTF_8));store.finishWrite(out);}catch(Exception ignored){if(out!=null)store.failWrite(out);}
   return fresh;
  }catch(Exception ignored){return previous;}finally{if(c!=null)c.disconnect();}
 }
 private static final long FRESH_CATALOG_MS=20L*60L*1000L;
 private AtomicFile cache(){return new AtomicFile(new File(dir,"live-v3-complete-providers.bin"));}
 private List<CatalogEntry> cached(){try(InputStream in=cache().openRead()){return CatalogCodec.read(in,"live");}catch(Exception ignored){return Collections.emptyList();}}
 private void save(List<CatalogEntry> rows){AtomicFile file=cache();FileOutputStream out=null;try{out=file.startWrite();CatalogCodec.write(out,rows);file.finishWrite(out);}catch(Exception ignored){if(out!=null)file.failWrite(out);}}
 void load(String type,ProgressiveCatalog.Listener listener)throws Exception{load(type,listener,false);}
 void load(String type,ProgressiveCatalog.Listener listener,boolean forceRefresh)throws Exception{
  if(!type.equals("live"))throw new IOException("Live TV only");
  List<CatalogEntry> previous=cached();
  long age=System.currentTimeMillis()-cache().getBaseFile().lastModified();
  if(!forceRefresh && !previous.isEmpty() && age>=0 && age<FRESH_CATALOG_MS){
    successful=2;
    listener.update(previous,true,2);
    return;
  }
  List<ProgressiveCatalog.Source> sources=Arrays.asList(preview->source(primary,false,preview,forceRefresh),preview->source(secondary,true,preview,forceRefresh));
  ProgressiveCatalog.loadStreaming(sources,previous,(rows,complete,count)->{
    if(complete)successful=count;
    listener.update(rows,complete,count);
    // A one-source outage must never replace the last complete two-source snapshot.
    if(complete && count==sources.size() && !rows.isEmpty()
         && (previous.isEmpty() || rows.size()>=previous.size()/2))save(rows);
  });
 }
 private List<CatalogEntry> source(JSONObject source,boolean second,ProgressiveCatalog.Preview preview,boolean forceRefresh)throws Exception{Exception last=null;for(int attempt=0;attempt<2;attempt++)try{return sourceOnce(source,second,preview,forceRefresh);}catch(Exception e){last=e;if(Thread.currentThread().isInterrupted())throw e;}throw last;}
 private static String stringValue(JsonReader r)throws Exception{if(r.peek()==JsonToken.STRING||r.peek()==JsonToken.NUMBER)return r.nextString();if(r.peek()==JsonToken.BEGIN_ARRAY){List<String> values=new ArrayList<>();r.beginArray();while(r.hasNext()){String value=stringValue(r);if(!value.isEmpty())values.add(value);}r.endArray();return String.join(",",values);}r.skipValue();return "";}
 private static String categoryHint(Map<String,String> names,String ids){StringBuilder hint=new StringBuilder();for(String id:ids.split(",")){String name=names.get(id);if(name!=null)hint.append(name).append(" ");}return hint.toString();}
 private List<CatalogEntry> sourceOnce(JSONObject source,boolean second,ProgressiveCatalog.Preview preview,boolean forceRefresh)throws Exception{Map<String,String> countries=categories(source,second,forceRefresh);HttpURLConnection c=connect(api(source,"get_live_streams"));List<CatalogEntry> rows=new ArrayList<>();try{if(c.getResponseCode()!=200)throw new IOException("Channels unavailable");try(JsonReader reader=new JsonReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){reader.beginArray();while(reader.hasNext()){if(Thread.currentThread().isInterrupted())throw new IOException("Cancelled");int id=0;String title="",poster="",category="";reader.beginObject();while(reader.hasNext()){String key=reader.nextName();if(reader.peek()==JsonToken.NULL){reader.nextNull();continue;}switch(key){case "stream_id":try{id=Integer.parseInt(stringValue(reader));}catch(NumberFormatException ignored){}break;case "name":title=stringValue(reader);break;case "stream_icon":poster=stringValue(reader);break;case "category_id":category=stringValue(reader);break;default:reader.skipValue();}}reader.endObject();if(id<=0||id>=1000000000||title.isEmpty())continue;if(second)id+=1000000000;poster=PosterMetadata.resolve(poster,source.getString("server"));String categoryName=categoryHint(countries,category);rows.add(new CatalogEntry(id,title,"m3u8",poster,categoryName,"live",0));rows.add(new CatalogEntry(id,title,"ts",poster,categoryName,"live",0));if(rows.size()==32||rows.size()==256||rows.size()==1024||rows.size()==4096)preview.accept(new ArrayList<>(rows));}reader.endArray();}}finally{c.disconnect();}if(rows.isEmpty())throw new IOException("Empty channel catalog");return rows;}
 String stream(CatalogEntry entry){if(!entry.type.equals("live"))throw new IllegalArgumentException("Live TV only");JSONObject source=entry.id>=1000000000?secondary:primary;int id=entry.id>=1000000000?entry.id-1000000000:entry.id;try{return source.getString("server")+"/live/"+enc(source.getString("username"))+"/"+enc(source.getString("password"))+"/"+id+"."+entry.extension;}catch(Exception e){throw new IllegalStateException("Source unavailable");}}
 Map<String,String> headersForUri(String uri){return Collections.emptyMap();}
 List<String> posters(CatalogEntry e){return Collections.emptyList();}void clearPosterCache(){}boolean partialSources(){return successful<2;}
}
