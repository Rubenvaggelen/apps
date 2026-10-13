package com.theone.cinema;
import org.json.*;import java.io.*;import java.util.*;
final class BootstrapCatalog {
 static List<CatalogEntry> read(InputStream stream,String type)throws Exception {
  if(!type.equals("movies")&&!type.equals("series"))return Collections.emptyList();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=stream.read(block))!=-1){bytes.write(block,0,n);if(bytes.size()>2*1024*1024)throw new IOException("Invalid starter catalog");}
  JSONArray rows=new JSONArray(bytes.toString("UTF-8"));if(rows.length()>512)throw new IOException("Invalid starter catalog");List<CatalogEntry> entries=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);int id=row.getInt("id");String title=row.getString("title");if(id<=0||id>=1000000000||!type.equals(row.getString("type"))||row.optInt("is_adult")!=0||CatalogEntry.adultTitle(title))throw new IOException("Invalid starter catalog");entries.add(new CatalogEntry(id,title,row.optString("extension","mp4"),row.optString("poster"),"",type,row.optLong("added"),row.optString("movieId")));}return entries;
 }
}
