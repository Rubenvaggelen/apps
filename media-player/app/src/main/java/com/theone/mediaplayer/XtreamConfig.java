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

    private XtreamConfig(String server, String username, String password) {
        this.server = normalize(server);
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.trim();
    }

    public static XtreamConfig load(Context context) {
        XtreamConfig primary = loadPrimary(context);
        try (InputStream in = context.getAssets().open("private_xtream_secondary.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            JSONObject json = new JSONObject(bytes.toString("UTF-8"));
            XtreamConfig source = new XtreamConfig(json.optString("server"), json.optString("username"), json.optString("password"));
            if (source.isConfigured() && !(source.server.equals(primary.server) && source.username.equals(primary.username))) primary.secondary = source;
        } catch (Exception ignored) { }
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
            String raw = source.download(source.account() + "&action=" + query(action) + "&" + (category != null ? "category_id" : "series_id") + "=" + query(id));
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
        org.json.JSONArray merged = new org.json.JSONArray();
        int successful = 0;
        for (int index=0; index<(secondary==null?1:2); index++) {
            XtreamConfig source = index==0 ? this : secondary;
            try {
                org.json.JSONArray rows = decorate(new org.json.JSONArray(source.download(source.account()+"&action="+query(action))), index==1, action);
                for (int i=0;i<rows.length();i++) merged.put(rows.get(i));
                successful++;
            } catch (Exception ignored) { }
        }
        if(successful==0) throw new java.io.IOException("Catalog unavailable");
        return merged.toString();
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
        connection.setConnectTimeout(15000); connection.setReadTimeout(45000); connection.setUseCaches(false);
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

