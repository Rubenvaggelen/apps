package com.theone.cinema.tv;
import org.json.*;
import java.net.URL;import java.util.*;
final class PosterMetadata {
 static String resolve(String value,String base){
  if(value==null)return "";String s=value.trim();if(s.isEmpty()||s.equalsIgnoreCase("null")||s.contains("\n")||s.contains("\r"))return "";
  try{URL url=new URL(new URL(base.isEmpty()?"https://invalid.example/":base),s);if((!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))||url.getHost().isEmpty()||url.getHost().equals("invalid.example"))return "";return url.toExternalForm();}catch(Exception ignored){return "";}
 }
 static List<String> candidates(JSONObject data,String base){
  LinkedHashSet<String> result=new LinkedHashSet<>();if(data==null)return new ArrayList<>();JSONObject[] objects={data.optJSONObject("info"),data.optJSONObject("movie_data"),data};
  for(JSONObject object:objects){if(object==null)continue;for(String key:new String[]{"movie_image","cover","cover_big","poster","stream_icon","poster_path"})add(result,object.opt(key),base);}
  for(JSONObject object:objects)if(object!=null)add(result,object.opt("backdrop_path"),base);
  return new ArrayList<>(result);
 }
 private static void add(Set<String> result,Object value,String base){if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)add(result,a.opt(i),base);}else if(value instanceof String){String url=resolve((String)value,base);if(!url.isEmpty())result.add(url);}}
 static String choose(JSONObject data,String base){List<String> urls=candidates(data,base);return urls.isEmpty()?"":urls.get(0);}
}