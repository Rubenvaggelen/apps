package com.theone.mediaplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class XtreamConfig {
    public final String server;
    public final String username;
    public final String password;
    private XtreamConfig secondary;
    private java.io.File catalogCacheDir;
    private volatile boolean lastLiveCatalogPartial;
    private volatile boolean forceLiveCatalogRefresh;

    /** True if at least one configured provider could not supply a live catalog. */
    public boolean isLastLiveCatalogPartial(){return lastLiveCatalogPartial;}

    /** Retry a previously missing provider without throwing away the last complete catalog. */
    public void refreshLiveCatalog(){forceLiveCatalogRefresh=true;}


    private XtreamConfig(String server, String username, String password) {
        this.server = normalize(server);
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.trim();
    }

    public static XtreamConfig load(Context context) {
        XtreamConfig primary = loadPrimary(context);
        primary.catalogCacheDir = new java.io.File(context.getCacheDir(),"theone_xtream_catalog");
        try (InputStream in = context.getAssets().open("private_xtream_secondary.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            JSONObject json = new JSONObject(bytes.toString("UTF-8"));
            XtreamConfig source = new XtreamConfig(json.optString("server"), json.optString("username"), json.optString("password"));
            if (source.isConfigured() && !(source.server.equals(primary.server) && source.username.equals(primary.username))) primary.secondary = source;
        } catch (Exception ignored) { }
        if (!primary.isConfigured() && primary.secondary != null && primary.secondary.isConfigured()) {
            // Recovery path for TVs upgrading from a build with placeholder
            // primary assets: keep Live TV usable through the valid second
            // provider. An authenticated primary will be restored by v1027.
            XtreamConfig fallback = primary.secondary;
            fallback.catalogCacheDir = primary.catalogCacheDir;
            return fallback;
        }
        return primary;
    }

    private static XtreamConfig loadPrimary(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("media_player", Context.MODE_PRIVATE);

        XtreamConfig saved = new XtreamConfig(
                prefs.getString("xtream_server", ""),
                prefs.getString("xtream_user", ""),
                prefs.getString("xtream_pass", "")
        );
        if (saved.isConfigured()) {
            return saved;
        }

        try (InputStream in = context.getAssets().open("private_xtream.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder raw = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) raw.append(line);
            JSONObject json = new JSONObject(raw.toString());

            XtreamConfig bundled = new XtreamConfig(
                    json.optString("server", ""),
                    json.optString("username", ""),
                    json.optString("password", "")
            );

            if (bundled.isConfigured()) {
                prefs.edit()
                        .putString("source_type", "XTREAM")
                        .putString("xtream_server", bundled.server)
                        .putString("xtream_user", bundled.username)
                        .putString("xtream_pass", bundled.password)
                        .apply();
            }

            return bundled;
        } catch (Throwable ignored) {
            return new XtreamConfig("", "", "");
        }
    }

    public boolean isConfigured() {
        return server.startsWith("http")
                && !username.isEmpty()
                && !password.isEmpty()
                && !username.startsWith("PRIVATE_")
                && !password.startsWith("PRIVATE_");
    }

    public String account() {
        return server + "/player_api.php?username=" + query(username)
                + "&password=" + query(password);
    }

    public String api(String action) {
        return "theone-xtream://catalog?action=" + query(action);
    }

    public String api(String action, String key, String value) {
        return api(action) + "&" + query(key) + "=" + query(value);
    }

    public String fetchCatalog(String url) throws Exception {
        java.util.Map<String,String> request = new java.util.HashMap<>();
        for(String pair:new java.net.URI(url).getRawQuery().split("&")){String[] parts=pair.split("=",2);request.put(java.net.URLDecoder.decode(parts[0],"UTF-8"),parts.length>1?java.net.URLDecoder.decode(parts[1],"UTF-8"):"");}
        String action = request.get("action");
        if (action == null || !action.matches("get_(live_categories|vod_categories|series_categories|live_streams|vod_streams|series|series_info)")) throw new java.io.IOException("Invalid catalog action");
        String category = request.get("category_id");
        String series = request.get("series_id");
        String scoped = category != null ? category : series;
        boolean second = scoped != null && scoped.startsWith("source2:");
        if (scoped != null) {
            XtreamConfig source = second ? secondary : this;
            if (source == null) throw new java.io.IOException("Source unavailable");
            String id = second ? scoped.substring(8) : scoped;
            if (!id.matches("[0-9]+")) throw new java.io.IOException("Invalid ID");
            // Large series categories must not be downloaded again every time
            // users return from an episode. Keep independent provider/category
            // snapshots; only use a stale snapshot when that source fails.
            boolean cacheSeriesCategory = category != null && "get_series".equals(action);
            java.io.File scopedCache = cacheSeriesCategory && catalogCacheDir != null
                ? new java.io.File(catalogCacheDir,"catalog-"+hashCacheKey(
                    "series-category-v1|"+source.server+"|"+source.username+"|"+id)+".json")
                : null;
            String raw = scopedCache==null ? null : readCachedCatalog(scopedCache,false);
            if(raw==null){
                try {
                    raw = source.download(source.account() + "&action=" + query(action) +
                        "&" + (category != null ? "category_id" : "series_id") + "=" + query(id));
                    if(cacheSeriesCategory){
                        org.json.JSONArray rows=new org.json.JSONArray(raw);
                        if(rows.length()>0)writeCachedCatalog(scopedCache,raw);
                    }
                }catch(Exception unavailable){
                    String previous=scopedCache==null ? null : readCachedCatalog(scopedCache,true);
                    if(previous==null)throw unavailable;
                    raw=previous;
                }
            }
            if (series != null) {
                JSONObject result = new JSONObject(raw);
                if (second) {
                    JSONObject seasons = result.optJSONObject("episodes");
                    if (seasons != null) { java.util.Iterator<String> keys = seasons.keys(); while (keys.hasNext()) {
                        org.json.JSONArray episodes = seasons.optJSONArray(keys.next());
                        if (episodes == null) continue;
                        for (int i=0;i<episodes.length();i++) { JSONObject row=episodes.optJSONObject(i); if(row!=null) prefixId(row,"id"); }
                    }}
                }
                return result.toString();
            }
            return decorate(new org.json.JSONArray(raw), second, action).toString();
        }
        // The country picker requests the entire TV catalog. A short, private
        // disk cache avoids re-downloading tens of thousands of rows on every
        // return from playback. Never expose usernames or URLs in filenames.
        // v2 deliberately ignores the old aggregate cache: prior releases
        // incorrectly stored partial results when one provider timed out.
        boolean live="get_live_streams".equals(action);
        // Also protect the full series library. The two providers can return
        // thousands of shows; repeated uncached downloads caused timeouts.
        boolean cacheable=live || action.endsWith("_categories") || "get_series".equals(action);
        boolean force=live && forceLiveCatalogRefresh;
        if(live){forceLiveCatalogRefresh=false;lastLiveCatalogPartial=false;}
        String cacheIdentity = "complete-v2|" + action + "|" + server + "|" + username + "|" +
                (secondary==null?"":secondary.server+"|"+secondary.username);
        java.io.File cacheFile = catalogCacheDir==null?null:
            new java.io.File(catalogCacheDir,"catalog-"+hashCacheKey(cacheIdentity)+".json");
        String previous=cacheable && !force?readCachedCatalog(cacheFile,false):null;
        if(previous!=null)return previous;

        java.util.concurrent.atomic.AtomicBoolean usedStaleProvider=new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.List<java.util.concurrent.Callable<org.json.JSONArray>> jobs=new java.util.ArrayList<>();
        for(int index=0;index<(secondary==null?1:2);index++){
            final int sourceIndex=index;
            jobs.add(()->{
                XtreamConfig source=sourceIndex==0?this:secondary;
                // Keep a separate known-good catalog for EACH provider. A slow
                // source must never erase the other provider's channel list.
                String key="provider-v2|"+action+"|"+source.server+"|"+source.username;
                java.io.File providerFile=catalogCacheDir==null?null:
                    new java.io.File(catalogCacheDir,"catalog-"+hashCacheKey(key)+".json");
                String cached=cacheable&&!force?readCachedCatalog(providerFile,false):null;
                if(cached!=null)return decorate(new org.json.JSONArray(cached),sourceIndex==1,action);
                try{
                    String raw=source.download(source.account()+"&action="+query(action));
                    org.json.JSONArray rows=new org.json.JSONArray(raw);
                    if(live && rows.length()==0)throw new java.io.IOException("Provider supplied no live channels");
                    String previousProvider=cacheable?readCachedCatalog(providerFile,true):null;
                    if(previousProvider!=null){
                        org.json.JSONArray last=new org.json.JSONArray(previousProvider);
                        // Preserve a known-good provider listing if the latest
                        // response mysteriously loses most of its channels.
                        if(suspiciousCatalogShrink(last,rows,live)){
                            usedStaleProvider.set(true);
                            return decorate(last,sourceIndex==1,action);
                        }
                    }
                    if(cacheable && rows.length()>0)writeCachedCatalog(providerFile,raw);
                    return decorate(rows,sourceIndex==1,action);
                }catch(Exception error){
                    String stale=cacheable?readCachedCatalog(providerFile,true):null;
                    if(stale!=null){
                        usedStaleProvider.set(true);
                        return decorate(new org.json.JSONArray(stale),sourceIndex==1,action);
                    }
                    throw error;
                }
            });
        }
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(jobs.size());
        org.json.JSONArray merged=new org.json.JSONArray();
        int completed=0;
        try{
            // 18 seconds was too short for a large IPTV catalog on Sony TVs.
            // A 70-second shared deadline is used only on cache misses.
            java.util.List<java.util.concurrent.Future<org.json.JSONArray>> futures =
                pool.invokeAll(jobs,70,java.util.concurrent.TimeUnit.SECONDS);
            for(java.util.concurrent.Future<org.json.JSONArray> future:futures){
                if(future.isCancelled())continue;
                try{
                    org.json.JSONArray rows=future.get();
                    for(int j=0;j<rows.length();j++)merged.put(rows.get(j));
                    completed++;
                }catch(Exception ignored){}
            }
        }finally{pool.shutdownNow();}
        boolean partial=completed<jobs.size()||usedStaleProvider.get();
        if(live)lastLiveCatalogPartial=partial;
        if(partial){
            // Prefer a previous COMPLETE two-source snapshot over an incomplete
            // current scan; never overwrite it with a single-provider result.
            String stale=cacheable?readCachedCatalog(cacheFile,true):null;
            if(stale!=null)return stale;
        }
        if(completed==0)throw new java.io.IOException("Catalog unavailable");
        String combined=merged.toString();
        if(cacheable && !partial)writeCachedCatalog(cacheFile,combined);
        return combined;
    }


    /** Deliver series rows as they arrive; do not wait for both large provider responses. */
    public interface SeriesBatchListener {
        void onBatch(org.json.JSONArray rows, boolean secondarySource);
    }

    public boolean streamSeriesCatalog(int requestedBatchSize, SeriesBatchListener listener) throws Exception {
        if(!isConfigured())throw new java.io.IOException("Source unavailable");
        final int batchSize=Math.max(25,Math.min(requestedBatchSize,250));
        java.util.List<java.util.concurrent.Callable<Boolean>> workers=new java.util.ArrayList<>();
        workers.add(()->streamSeriesProvider(this,false,batchSize,listener));
        if(secondary!=null&&secondary.isConfigured())
            workers.add(()->streamSeriesProvider(secondary,true,batchSize,listener));
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(workers.size());
        int successful=0;
        try{
            // The caller receives the first rows immediately. A slow second provider
            // must not block rendering, and timed-out work is cancelled.
            for(java.util.concurrent.Future<Boolean> future:
                pool.invokeAll(workers,75,java.util.concurrent.TimeUnit.SECONDS)){
                if(future.isCancelled())continue;
                try{if(future.get())successful++;}catch(Exception ignored){}
            }
        }finally{pool.shutdownNow();}
        return successful==workers.size()&&successful>0;
    }

    private boolean streamSeriesProvider(XtreamConfig source, boolean second, int batchSize,
                                         SeriesBatchListener listener) {
        java.io.File providerFile=catalogCacheDir==null?null:new java.io.File(catalogCacheDir,
            "catalog-"+hashCacheKey("provider-v2|get_series|"+source.server+"|"+source.username)+".json");
        String cached=readCachedCatalog(providerFile,false);
        if(cached!=null){
            try{
                streamSeriesRows(new java.io.StringReader(cached),null,null,second,batchSize,listener);
                return true;
            }catch(Exception ignored){/* Damaged cache: try the source. */}
        }
        java.net.HttpURLConnection connection=null;
        java.io.File pending=null;
        java.io.Writer writer=null;
        try{
            connection=(java.net.HttpURLConnection)new java.net.URL(
                source.account()+"&action=get_series").openConnection();
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent","TheOneMediaPlayer/2.4.1");
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Series catalog unavailable");
            if(providerFile!=null){
                java.io.File parent=providerFile.getParentFile();
                if(parent!=null&&(parent.isDirectory()||parent.mkdirs())){
                    pending=new java.io.File(parent,providerFile.getName()+".stream.tmp");
                    writer=new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(pending),java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            try(java.io.Reader input=new java.io.InputStreamReader(
                connection.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)){
                streamSeriesRows(input,writer,pending,second,batchSize,listener);
                writer=null;
            }
            if(pending!=null&&pending.exists()&&pending.length()<64L*1024L*1024L)
                if(!pending.renameTo(providerFile))pending.delete();
            return true;
        }catch(Exception unavailable){
            String stale=readCachedCatalog(providerFile,true);
            if(stale!=null)try{
                streamSeriesRows(new java.io.StringReader(stale),null,null,second,batchSize,listener);
                return true;
            }catch(Exception ignored){}
            return false;
        }finally{
            if(writer!=null)try{writer.close();}catch(Exception ignored){}
            if(pending!=null&&pending.exists())pending.delete();
            if(connection!=null)connection.disconnect();
        }
    }

    private static void streamSeriesRows(java.io.Reader input, java.io.Writer snapshot,
            java.io.File pending,boolean second,int batchSize,SeriesBatchListener listener)throws Exception {
        com.google.gson.stream.JsonReader reader=new com.google.gson.stream.JsonReader(input);
        org.json.JSONArray batch=new org.json.JSONArray();
        boolean first=true;
        if(snapshot!=null)snapshot.write("[");
        reader.beginArray();
        while(reader.hasNext()){
            reader.beginObject();
            org.json.JSONObject row=new org.json.JSONObject();
            while(reader.hasNext()){
                String key=reader.nextName();
                // Only fields used by Media Player cards are retained.
                if(!("series_id".equals(key)||"stream_id".equals(key)||"category_id".equals(key)
                    ||"name".equals(key)||"title".equals(key)||"cover".equals(key)
                    ||"cover_big".equals(key)||"stream_icon".equals(key)||"poster".equals(key)
                    ||"rating".equals(key)||"year".equals(key)||"releaseDate".equals(key)
                    ||"release_date".equals(key)||"added".equals(key))){
                    reader.skipValue();
                    continue;
                }
                com.google.gson.stream.JsonToken type=reader.peek();
                if(type==com.google.gson.stream.JsonToken.STRING||type==com.google.gson.stream.JsonToken.NUMBER)
                    row.put(key,reader.nextString());
                else if(type==com.google.gson.stream.JsonToken.BOOLEAN)row.put(key,String.valueOf(reader.nextBoolean()));
                else reader.skipValue();
            }
            reader.endObject();
            if(snapshot!=null){
                if(!first)snapshot.write(",");
                snapshot.write(row.toString());
                // Prevent a huge catalog from exhausting disk on budget TVs.
                if(pending!=null&&pending.length()>64L*1024L*1024L)
                    throw new java.io.IOException("Series snapshot too large");
            }
            first=false;
            if(second){
                for(String key:new String[]{"series_id","stream_id","category_id"})
                    prefixId(row,key);
            }
            batch.put(row);
            if(batch.length()>=batchSize){
                listener.onBatch(batch,second);
                batch=new org.json.JSONArray();
            }
        }
        reader.endArray();
        if(snapshot!=null){snapshot.write("]");snapshot.close();}
        if(batch.length()>0)listener.onBatch(batch,second);
    }

    /** Keep previously known Dutch channels if the provider unexpectedly drops most of them. */
    static boolean suspiciousCatalogShrink(org.json.JSONArray previous,org.json.JSONArray now,boolean live){
        if(previous==null || now==null)return false;
        if(previous.length()>=100 && now.length()*4<previous.length())return true;
        if(!live)return false;
        int oldNl=countDutchPrefix(previous),newNl=countDutchPrefix(now);
        return oldNl>=20 && newNl*4<oldNl;
    }
    private static int countDutchPrefix(org.json.JSONArray rows){
        int count=0;
        for(int i=0;i<rows.length();i++){
            org.json.JSONObject row=rows.optJSONObject(i);
            if(row!=null && ChannelGuide.hasNlPrefix(row.optString("name",row.optString("title",""))))count++;
        }
        return count;
    }

    private String hashCacheKey(String value) {
        try {
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();
            for(int i=0;i<16;i++)hex.append(String.format(java.util.Locale.ROOT,"%02x",bytes[i]&255));
            return hex.toString();
        }catch(Exception error){return Integer.toHexString(value.hashCode());}
    }

    private String readCachedCatalog(java.io.File file,boolean allowStale) {
        try{
            if(file==null||!file.isFile()||file.length()>64*1024*1024)return null;
            long age=System.currentTimeMillis()-file.lastModified();
            if(age<0 || age>(allowStale?7L*24L*3600000L:12L*60000L))return null;
            byte[] data=java.nio.file.Files.readAllBytes(file.toPath());
            String value=new String(data,java.nio.charset.StandardCharsets.UTF_8);
            new org.json.JSONArray(value);
            return value;
        }catch(Exception ignored){return null;}
    }

    private void writeCachedCatalog(java.io.File file,String content) {
        if(file==null||content.length()>64*1024*1024)return;
        try{
            java.io.File parent=file.getParentFile();
            if(parent==null||(!parent.isDirectory()&&!parent.mkdirs()))return;
            java.io.File temp=new java.io.File(parent,file.getName()+".tmp");
            java.nio.file.Files.write(temp.toPath(),content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(!temp.renameTo(file))temp.delete();
        }catch(Exception ignored){}
    }

    private org.json.JSONArray decorate(org.json.JSONArray rows, boolean second, String action) throws Exception {
        if(!second) return rows;
        boolean categories = action.endsWith("categories");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row==null)continue;
            for(String key:new String[]{"stream_id","series_id","category_id"}) prefixId(row,key);
            // Provider is internal; never append source labels to category titles.
            for(String key:new String[]{"stream_icon","cover","cover_big","movie_image"}) {
                String value=row.optString(key,"");
                if(value.startsWith("/")) row.put(key,secondary.server+value);
            }
        }
        return rows;
    }

    private static void prefixId(JSONObject row,String key) throws Exception {
        if(row.has(key) && !row.isNull(key)) row.put(key,"source2:"+String.valueOf(row.get(key)));
    }

    private String download(String url) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection)new java.net.URL(url).openConnection();
        connection.setConnectTimeout(9000); connection.setReadTimeout(20000); connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent","TheOneMediaPlayer/2.4.1");
        try {
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Catalog unavailable");
            try(InputStream in=connection.getInputStream()) {
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;
                while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>100*1024*1024)throw new java.io.IOException("Catalog too large");}
                return out.toString("UTF-8");
            }
        } catch(Exception error) { throw new java.io.IOException("Catalog unavailable"); }
        finally {connection.disconnect();}
    }

    /** Identify the recovered second provider without changing its account or IDs. */
    public boolean isSecondaryStreamUrl(String url) {
        return secondary != null && secondary.isConfigured() && url != null
                && url.startsWith(secondary.server + "/");
    }

    public String stream(String type, String id, String extension) {
        if (id != null && id.startsWith("source2:")) {
            if (secondary == null) throw new IllegalStateException("Source unavailable");
            return secondary.stream(type, id.substring(8), extension);
        }
        if (id == null || !id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid ID");
        String prefix;
        if ("movie".equals(type)) prefix = "movie";
        else if ("series".equals(type)) prefix = "series";
        else prefix = "live";
        String ext = extension == null || extension.trim().isEmpty()
                ? ("live".equals(prefix) ? "ts" : "mp4")
                : extension.trim().replace(".", "");
        return server + "/" + prefix + "/" + query(username).replace("+", "%20") + "/" + query(password).replace("+", "%20")
                + "/" + id + "." + ext;
    }

    private static String query(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private static String normalize(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}

