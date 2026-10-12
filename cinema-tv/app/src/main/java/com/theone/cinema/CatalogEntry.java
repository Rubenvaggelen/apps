package com.theone.cinema;
import java.util.Locale;
public final class CatalogEntry {
 public final int id; public final String title, extension, poster, category, type; public final long added; public final int rank;
 public CatalogEntry(int id,String title,String extension,String poster,String category,String type,long added){this.id=id;this.title=title;this.extension=extension!=null&&extension.matches("[a-zA-Z0-9]{1,8}")?extension:"mp4";this.poster=poster==null?"":poster;this.category=category==null?"":category;this.type=type;this.added=added;this.rank=quality(title);}
 public String key(){return type+":"+normalize(title);}
 public static String normalize(String s){return s.toLowerCase(Locale.ROOT).replaceAll("(?i)\\b(2160p|1080p|720p|4k|uhd|fhd|hd|hevc|x264|x265|h264|h265)\\b", "").replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
 public static int quality(String s){String x=s.toLowerCase(Locale.ROOT);if(x.matches(".*(2160|\\b4k\\b|\\buhd\\b).*"))return 2160;if(x.matches(".*(1080|\\bfhd\\b).*"))return 1080;if(x.matches(".*(720|\\bhd\\b).*"))return 720;return 0;}
 public boolean isEpisode(){return type.equals("episode");}
}
