package com.theone.mediaplayer;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class XtreamCatalog {
    static final class Entry {
        final String source, kind, title, category, streamId, extension;
        Entry(String source, String kind, String title, String category, String streamId, String extension) {
            this.source=source; this.kind=kind; this.title=title; this.category=category; this.streamId=streamId; this.extension=extension;
        }
    }
    static final class Result {
        final List<Entry> entries = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }
    static Result load(SharedPreferences prefs, String kind) {
        Result result = new Result();
        for (int i=1;i<=2;i++) {
            String prefix="xtream_"+i+"_";
            String server=prefs.getString(prefix+"server", i==1 ? prefs.getString("xtream_server","") : "");
            String user=prefs.getString(prefix+"user", i==1 ? prefs.getString("xtream_user","") : "");
            String password=prefs.getString(prefix+"pass", i==1 ? prefs.getString("xtream_pass","") : "");
            if(server.isEmpty() || user.isEmpty() || password.isEmpty()) continue;
            String name=prefs.getString(prefix+"name","Bron "+i);
            try {
                String action="live".equals(kind)?"get_live_streams":"movie".equals(kind)?"get_vod_streams":"get_series";
                String endpoint=server.replaceAll("/+$","")+"/player_api.php?username="+encode(user)+"&password="+encode(password)+"&action="+action;
                JSONArray items=new JSONArray(fetch(endpoint));
                for(int n=0;n<items.length();n++) {
                    JSONObject item=items.optJSONObject(n);
                    if(item==null)continue;
                    String id=String.valueOf(item.opt("series".equals(kind)?"series_id":"stream_id"));
                    if("null".equals(id)||id.isEmpty())continue;
                    result.entries.add(new Entry(name,kind,item.optString("name","Zonder titel"),item.optString("category_id",""),id,item.optString("container_extension","mp4")));
                }
            } catch(Exception e) { result.errors.add(name+": catalogus kon niet worden geladen ("+e.getClass().getSimpleName()+")"); }
        }
        return result;
    }
    static String streamUrl(SharedPreferences prefs, Entry entry) {
        for(int i=1;i<=2;i++) {
            String prefix="xtream_"+i+"_";
            if(!entry.source.equals(prefs.getString(prefix+"name","Bron "+i)))continue;
            String server=prefs.getString(prefix+"server",i==1?prefs.getString("xtream_server",""):"");
            String user=prefs.getString(prefix+"user",i==1?prefs.getString("xtream_user",""):"");
            String pass=prefs.getString(prefix+"pass",i==1?prefs.getString("xtream_pass",""):"");
            if("series".equals(entry.kind))return "";
            String extension="live".equals(entry.kind)?"ts":entry.extension.replaceAll("[^a-zA-Z0-9]","");
            return server.replaceAll("/+$","")+"/"+("live".equals(entry.kind)?"live":"movie")+"/"+encode(user)+"/"+encode(pass)+"/"+entry.streamId+"."+extension;
        }
        return "";
    }
    static String fetch(String address) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(18000);
        c.setRequestProperty("Accept","application/json");
        try {
            if(c.getResponseCode()!=200)throw new java.io.IOException("HTTP "+c.getResponseCode());
            BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();String line;
            while((line=reader.readLine())!=null) {out.append(line);if(out.length()>12000000)throw new java.io.IOException("Catalog too large");}
            return out.toString();
        } finally {c.disconnect();}
    }
    private static String encode(String v) {
        try {return URLEncoder.encode(v,"UTF-8").replace("+","%20");}catch(Exception e){return "";}
    }
}
