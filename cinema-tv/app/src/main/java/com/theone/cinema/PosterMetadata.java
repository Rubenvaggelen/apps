package com.theone.cinema;
import org.json.*;
import java.net.URL;
final class PosterMetadata {
 static String resolve(String value,String base){
  if(value==null)return "";String s=value.trim();if(s.isEmpty()||s.equalsIgnoreCase("null")||s.contains("\n")||s.contains("\r"))return "";
  try{if(s.startsWith("//"))s=new URL(base).getProtocol()+":"+s;else if(s.startsWith("/"))s=base.replaceAll("/+$","")+s;
   URL url=new URL(s);if((!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))||url.getHost().isEmpty())return "";return s;
  }catch(Exception ignored){return "";}
 }
 static String choose(JSONObject data,String base){
  if(data==null)return "";JSONObject[] objects={data.optJSONObject("info"),data.optJSONObject("movie_data"),data};
  for(JSONObject object:objects){if(object==null)continue;for(String key:new String[]{"movie_image","cover","cover_big","poster","stream_icon"}){String value=resolve(object.optString(key,""),base);if(!value.isEmpty())return value;}}
  for(JSONObject object:objects){if(object==null)continue;JSONArray backdrop=object.optJSONArray("backdrop_path");String value=resolve(backdrop!=null?backdrop.optString(0):object.optString("backdrop_path",""),base);if(!value.isEmpty())return value;}
  return "";
 }
}
