package com.theone.series;
import android.content.Context;import android.util.JsonReader;import android.util.JsonToken;
import org.json.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;
final class MediaRepository {
 private final PlaybackPriority background;
 private boolean offlineTest;void enableOfflineForTest(){if(!BuildConfig.DEBUG)throw new IllegalStateException();offlineTest=true;}
 private final Context context;private final File cacheDir;private final String server,token;private JSONObject primary,secondary;private volatile int lastSuccessful=2;
 MediaRepository(Context c)throws Exception{this(c,new PlaybackPriority());}
 MediaRepository(Context c,PlaybackPriority background)throws Exception{this.background=background;context=c;cacheDir=new File(c.getFilesDir(),"catalogs");cacheDir.mkdirs();try(InputStream in=c.getAssets().open("private_cinema.json")){JSONObject x=new JSONObject(read(in));server=x.getString("relay_url");token=x.getString("relay_token");}if(!server.startsWith("https://rubenvanaggelen.com/")||token.isEmpty())throw new IOException("Source unavailable");try(InputStream in=c.getAssets().open("private_xtream_primary.json")){primary=new JSONObject(read(in));}try(InputStream in=c.getAssets().open("private_xtream_secondary.json")){secondary=new JSONObject(read(in));}catch(Exception ignored){}}
 private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){throw new IllegalArgumentException();}}
 private static String read(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>100*1024*1024)throw new IOException("Catalog exceeds limit");}return out.toString("UTF-8");}
 // Limit the memory required for a single series-info response. Movie streams
 // and catalog loading remain unchanged.
 static String readEpisodeResponse(InputStream in)throws Exception{
  final int maxBytes=12*1000*1000;ByteArrayOutputStream out=new ByteArrayOutputStream();
  byte[] buffer=new byte[8192];int n;
  while((n=in.read(buffer))!=-1){
   if(Thread.currentThread().isInterrupted())throw new IOException("Episode request cancelled");
   if(n>maxBytes-out.size())throw new IOException("Episode response exceeds safe memory budget");
   out.write(buffer,0,n);
  }
  return out.toString("UTF-8");
 }
 private String api(JSONObject source,String action)throws Exception{return source.getString("server")+"/player_api.php?username="+enc(source.getString("username"))+"&password="+enc(source.getString("password"))+"&action="+action;}
 private String secondaryApi(String action)throws Exception{return api(secondary,action);}
 private List<CatalogEntry> starter(String type){try(InputStream in=new java.util.zip.GZIPInputStream(context.getAssets().open("bootstrap-"+type+".bin"))){return BootstrapCatalog.read(in,type);}catch(Exception ignored){return Collections.emptyList();}}
 private List<CatalogEntry> initial(String type,ProgressiveCatalog.Listener listener){List<CatalogEntry> seed=starter(type);if(!seed.isEmpty())listener.update(seed,false,0);List<CatalogEntry> disk=cached(type);return disk.isEmpty()?seed:disk;}
 private HttpURLConnection connection(String url,boolean second,int readTimeout)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(readTimeout);if(url.startsWith(server+"?"))c.setRequestProperty("X-Cinema-Token",token);return c;}
 private android.util.AtomicFile cacheFile(String type){return new android.util.AtomicFile(new File(cacheDir,"series-v1.bin"));}
 private List<CatalogEntry> cached(String type){try(InputStream in=cacheFile(type).openRead()){return CatalogCodec.read(in,type);}catch(Exception ignored){return Collections.emptyList();}}
 private void save(String type,List<CatalogEntry> entries){android.util.AtomicFile file=cacheFile(type);FileOutputStream out=null;try{out=file.startWrite();CatalogCodec.write(out,entries);file.finishWrite(out);}catch(Exception ignored){if(out!=null)file.failWrite(out);}}
 List<CatalogEntry> load(String type)throws Exception{List<CatalogEntry> result=new ArrayList<>();load(type,(entries,complete,successful)->{if(complete)result.addAll(entries);});return result;}
 void load(String type,ProgressiveCatalog.Listener listener)throws Exception{
  if(!type.equals("series"))throw new IOException("Series only");if(offlineTest){listener.update(starter(type),true,0);return;}List<ProgressiveCatalog.Source> sources=new ArrayList<>();
  sources.add(preview->loadSource(api(primary,"get_series"),false,preview));if(secondary!=null)sources.add(preview->loadSource(secondaryApi("get_series"),true,preview));
  ProgressiveCatalog.loadStreaming(sources,initial(type,listener),(entries,complete,successful)->{lastSuccessful=successful;listener.update(entries,complete,successful);if(successful>0)save(type,entries);});
 }
 private List<CatalogEntry> loadSource(String url,boolean second,ProgressiveCatalog.Preview preview)throws Exception{HttpURLConnection c=connection(url,second,45000);List<CatalogEntry> list=new ArrayList<>();PlaybackPriority.Ticket request=background.register(c::disconnect);try{background.ensureAllowed();if(c.getResponseCode()!=200)throw new IOException("Catalog unavailable");try(JsonReader reader=new JsonReader(new InputStreamReader(background.checked(c.getInputStream()),StandardCharsets.UTF_8))){reader.beginArray();while(true){background.ensureAllowed();if(!reader.hasNext())break;
  int id=0;String title="",poster="",category="",movieId="";long added=0;reader.beginObject();while(reader.hasNext()){String key=reader.nextName();if(reader.peek()==JsonToken.NULL){reader.nextNull();continue;}switch(key){case "series_id":id=(int)number(reader);break;case "name":title=reader.nextString();break;case "cover":case "stream_icon":String candidate=reader.nextString();if(poster.isEmpty()&&!candidate.equalsIgnoreCase("null"))poster=candidate;break;case "category_id":category=reader.nextString();break;case "tmdb":case "tmdb_id":String identifier=reader.nextString();if(movieId.isEmpty()&&validIdentifier(identifier))movieId=identifier;break;case "last_modified":case "added":added=Math.max(added,number(reader));break;default:reader.skipValue();}}reader.endObject();if(id<=0||id>=1000000000||title.isEmpty())continue;if(second)id+=1000000000;poster=PosterMetadata.resolve(poster,(second?secondary:primary).getString("server"));list.add(new CatalogEntry(id,title,"mp4",poster,category,"series",added,movieId));if(list.size()==256)preview.accept(new ArrayList<>(list));}reader.endArray();}}finally{request.close();c.disconnect();}return list;}
 // Match Media Player: fetch get_series_info one provider variant at a time.
 // A failing or oversized source must not hide valid episodes from another source.
 EpisodeCatalog.Result episodes(List<CatalogEntry> parents)throws Exception{
  List<CatalogEntry> all=new ArrayList<>();
  int successful=0;
  if(parents==null||parents.isEmpty())throw new IOException("No series sources");
  for(CatalogEntry parent:parents){
   if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Series lookup cancelled");
   if(parent==null||!"series".equals(parent.type))continue;
   try{
    List<CatalogEntry> part=episodeSource(parent);
    successful++;
    all.addAll(part);
   }catch(Exception error){
    // Keep other provider variants available, as in Media Player.
    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Series lookup cancelled");
   }
  }
  if(successful==0)throw new IOException("Episodes unavailable from both providers");
  return EpisodeCatalog.merge(all);
 }
 private List<CatalogEntry> episodeSource(CatalogEntry parent)throws Exception{boolean second=parent.id>=1000000000;String url=second?secondaryApi("get_series_info")+"&series_id="+(parent.id-1000000000):api(primary,"get_series_info")+"&series_id="+parent.id;HttpURLConnection c=connection(url,second,30000);PlaybackPriority.Ticket request=background.register(c::disconnect);try{background.ensureAllowed();if(c.getResponseCode()!=200)throw new IOException("Episodes unavailable");if(c.getContentLengthLong()>12L*1000*1000)throw new IOException("Episode response exceeds safe memory budget");JSONObject data=new JSONObject(readEpisodeResponse(background.checked(c.getInputStream())));return EpisodeCatalog.parse(data,parent,second?secondary.getString("server"):primary.getString("server"),second);}finally{request.close();c.disconnect();}}
 List<String> posters(CatalogEntry entry)throws Exception{if(entry.isEpisode())return Collections.emptyList();boolean second=entry.id>=1000000000;if(second&&secondary!=null){HttpURLConnection c=connection(secondaryApi("get_series_info")+"&series_id="+(entry.id-1000000000),true,15000);PlaybackPriority.Ticket request=background.register(c::disconnect);try{background.ensureAllowed();if(c.getResponseCode()==200)return PosterMetadata.candidates(new JSONObject(read(background.checked(c.getInputStream()))),secondary.getString("server"));}finally{request.close();c.disconnect();}}return Collections.emptyList();}
 void clearPosterCache(){}
 private static boolean validIdentifier(String s){if(s.length()==0||s.length()>10||s.charAt(0)<'1'||s.charAt(0)>'9')return false;for(int i=1;i<s.length();i++)if(s.charAt(i)<'0'||s.charAt(i)>'9')return false;return true;}
 private static long number(JsonReader r)throws Exception{try{return Long.parseLong(r.nextString());}catch(NumberFormatException e){return 0;}}
 boolean partialSources(){return secondary!=null&&lastSuccessful<2;}
 Map<String,String> headersForUri(String uri){return uri.startsWith(server+"?")?Collections.singletonMap("X-Cinema-Token",token):Collections.emptyMap();}
 String stream(CatalogEntry e){if(!e.isEpisode())throw new IllegalArgumentException("Select an episode");if(e.id>=1000000000&&secondary!=null)try{return secondary.getString("server")+"/series/"+enc(secondary.getString("username"))+"/"+enc(secondary.getString("password"))+"/"+(e.id-1000000000)+"."+e.extension;}catch(Exception error){throw new IllegalStateException("Source unavailable");}try{return primary.getString("server")+"/series/"+enc(primary.getString("username"))+"/"+enc(primary.getString("password"))+"/"+e.id+"."+e.extension;}catch(Exception error){throw new IllegalStateException("Source unavailable");}}
}
