package com.theone.cinema;
import java.util.*;
import java.util.regex.*;
public final class CatalogEntry {
 private static final String LANG="EN|ENG|ENGLISH|NL|NLD|DUTCH|FR|FRA|FRENCH|DE|DEU|GERMAN|ES|SPA|SPANISH|IT|ITA|ITALIAN|PT|POR|PORTUGUESE|TR|TUR|TURKISH|RU|RUS|RUSSIAN|AR|ARA|ARABIC|PL|POL|POLISH|HI|HIN|HINDI|MULTI";
 private static final Pattern PREFIX=Pattern.compile("^\\s*(?:\\[("+LANG+")\\]\\s*[-|:]?\\s*|("+LANG+")\\s*[-|]\\s*)",Pattern.CASE_INSENSITIVE);
 private static final Pattern SUFFIX=Pattern.compile("(?:\\s+|\\s*[-|:]\\s*)(?:\\[("+LANG+")\\]|\\(("+LANG+")\\)|((?-i:EN|ENG|NL|NLD|FR|FRA|DE|DEU|ES|SPA|PT|POR|RU|RUS|AR|ARA|MULTI)))\\s*$",Pattern.CASE_INSENSITIVE);
 public final int id; public final String title,extension,poster,category,type,movieId;public final long added;public final int rank;
 public CatalogEntry(int id,String title,String extension,String poster,String category,String type,long added){this(id,title,extension,poster,category,type,added,"");}
 public CatalogEntry(int id,String title,String extension,String poster,String category,String type,long added,String movieId){this.id=id;this.title=title;this.extension=extension!=null&&extension.matches("[a-zA-Z0-9]{1,8}")?extension:"mp4";this.poster=poster==null?"":poster;this.category=category==null?"":category;this.type=type;this.added=added;this.rank=quality(title);this.movieId=movieId!=null&&movieId.matches("[1-9][0-9]{0,9}")?movieId:"";}
 public static LinkedHashMap<String,List<CatalogEntry>> group(List<CatalogEntry> entries){
  Map<String,Set<String>> aliases=new HashMap<>();for(CatalogEntry e:entries)if(!e.movieId.isEmpty())aliases.computeIfAbsent(e.type+":"+normalize(e.title),k->new HashSet<>()).add(e.movieId);
  LinkedHashMap<String,List<CatalogEntry>> groups=new LinkedHashMap<>();for(CatalogEntry original:entries){CatalogEntry e=original;Set<String> ids=aliases.get(e.type+":"+normalize(e.title));if(e.movieId.isEmpty()&&ids!=null&&ids.size()==1)e=new CatalogEntry(e.id,e.title,e.extension,e.poster,e.category,e.type,e.added,ids.iterator().next());groups.computeIfAbsent(e.key(),k->new ArrayList<>()).add(e);}for(List<CatalogEntry> xs:groups.values())xs.sort(PLAYBACK_ORDER);return groups;
 }
 public String key(){return type+":"+(movieId.isEmpty()?normalize(title):"tmdb:"+movieId);}
 public String legacyKey(){return type+":"+qualityFree(title).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
 private static String qualityFree(String s){return s.replaceAll("(?i)\\b(2160p|1080p|720p|4k|uhd|fhd|hd|hevc|x264|x265|h264|h265)\\b", "").trim();}
 private static String matchLanguage(Matcher m){for(int i=1;i<=m.groupCount();i++)if(m.group(i)!=null)return m.group(i).toUpperCase(Locale.ROOT);return "";}
 public static String language(String s){Matcher p=PREFIX.matcher(s);if(p.find())return canonical(matchLanguage(p));Matcher end=SUFFIX.matcher(qualityFree(s));return end.find()?canonical(matchLanguage(end)):"";}
 private static String canonical(String s){if(s.matches("EN|ENG|ENGLISH"))return "en";if(s.equals("MULTI"))return "multi";return s.toLowerCase(Locale.ROOT);}
 public static String normalize(String s){String t=qualityFree(s);t=PREFIX.matcher(t).replaceFirst("");t=SUFFIX.matcher(t).replaceFirst("");t=t.replaceFirst("(?i)^\\s*(?:NF|NETFLIX|AMAZON)\\s*[-|:]\\s*","");return t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
 private static int languageRank(CatalogEntry e){String l=language(e.title);return l.equals("en")?0:l.equals("multi")?1:l.isEmpty()?2:3;}
 public static final Comparator<CatalogEntry> PLAYBACK_ORDER=Comparator.comparingInt(CatalogEntry::languageRank).thenComparing(Comparator.comparingInt((CatalogEntry e)->e.rank).reversed()).thenComparing(Comparator.comparingLong((CatalogEntry e)->e.added).reversed()).thenComparingInt(e->e.id);
 public static int quality(String s){String x=s.toLowerCase(Locale.ROOT);if(x.matches(".*(2160|\\b4k\\b|\\buhd\\b).*"))return 2160;if(x.matches(".*(1080|\\bfhd\\b).*"))return 1080;if(x.matches(".*(720|\\bhd\\b).*"))return 720;return 0;}
 public static boolean adultCategory(String name){return Pattern.compile("(?:\\bxxx\\b|\\badult\\b|\\bporn(?:ography|o)?\\b|\\berotic(?:a)?\\b|18\\s*\\+|volwassen)",Pattern.CASE_INSENSITIVE).matcher(name==null?"":name).find();}
 public static boolean adultTitle(String name){return Pattern.compile("^\\s*(?:\\[xxx\\]|xxx\\s*\\||18\\s*\\+)",Pattern.CASE_INSENSITIVE).matcher(name==null?"":name).find();}
 public boolean isAdult(){return type.equals("xxx");}public boolean isEpisode(){return type.equals("episode");}
}
