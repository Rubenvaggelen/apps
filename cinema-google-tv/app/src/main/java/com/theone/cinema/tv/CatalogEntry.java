package com.theone.cinema.tv;
import java.util.*;
import java.util.regex.*;
public final class CatalogEntry {
 private static final String LANG="EN|ENG|ENGLISH|NL|NLD|DUTCH|FR|FRA|FRENCH|DE|DEU|GERMAN|ES|SPA|SPANISH|IT|ITA|ITALIAN|PT|POR|PORTUGUESE|TR|TUR|TURKISH|RU|RUS|RUSSIAN|AR|ARA|ARABIC|PL|POL|POLISH|HI|HIN|HINDI|MULTI";
 private static final Pattern PREFIX=Pattern.compile("^\\s*(?:\\[("+LANG+")\\]\\s*[-|:]?\\s*|("+LANG+")\\s*[-|]\\s*)",Pattern.CASE_INSENSITIVE);
 private static final Pattern SUFFIX=Pattern.compile("(?:\\s+|\\s*[-|:]\\s*)(?:\\[("+LANG+")\\]|\\(("+LANG+")\\)|((?-i:EN|ENG|NL|NLD|FR|FRA|DE|DEU|ES|SPA|PT|POR|RU|RUS|AR|ARA|MULTI)))\\s*$",Pattern.CASE_INSENSITIVE);
 private final String cachedKey,cachedLanguage,cachedLegacyKey;
 public final int id; public final String title,extension,poster,category,type,movieId;public final long added;public final int rank;
 public CatalogEntry(int id,String title,String extension,String poster,String category,String type,long added){this(id,title,extension,poster,category,type,added,"");}
 public CatalogEntry(int id,String title,String extension,String poster,String category,String type,long added,String movieId){this.id=id;this.title=title;this.extension=extension!=null&&extension.matches("[a-zA-Z0-9]{1,8}")?extension:"mp4";this.poster=poster==null?"":poster;this.category=category==null?"":category;this.type=type;this.added=added;this.rank=quality(title);this.movieId=movieId!=null&&movieId.matches("[1-9][0-9]{0,9}")?movieId:"";this.cachedKey=type+":"+(this.movieId.isEmpty()?normalize(title):"tmdb:"+this.movieId);this.cachedLanguage=language(title);this.cachedLegacyKey=type+":"+qualityFree(title).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
 public static LinkedHashMap<String,List<CatalogEntry>> group(List<CatalogEntry> entries){
  Map<String,Set<String>> aliases=new HashMap<>();for(CatalogEntry e:entries)if(!e.movieId.isEmpty())aliases.computeIfAbsent(e.type+":"+normalize(e.title),k->new HashSet<>()).add(e.movieId);
  LinkedHashMap<String,List<CatalogEntry>> groups=new LinkedHashMap<>();for(CatalogEntry original:entries){CatalogEntry e=original;Set<String> ids=aliases.get(e.type+":"+normalize(e.title));if(e.movieId.isEmpty()&&ids!=null&&ids.size()==1)e=new CatalogEntry(e.id,e.title,e.extension,e.poster,e.category,e.type,e.added,ids.iterator().next());groups.computeIfAbsent(e.key(),k->new ArrayList<>()).add(e);}for(List<CatalogEntry> xs:groups.values())xs.sort(PLAYBACK_ORDER);return groups;
 }
 public String key(){return cachedKey;}
 public String legacyKey(){return cachedLegacyKey;}
 private static String qualityFree(String s){return s.replaceAll("(?i)\\b(2160p|1080p|720p|4k|uhd|fhd|hd|hevc|x264|x265|h264|h265)\\b", "").trim();}
 private static String matchLanguage(Matcher m){for(int i=1;i<=m.groupCount();i++)if(m.group(i)!=null)return m.group(i).toUpperCase(Locale.ROOT);return "";}
 public static String language(String s){Matcher p=PREFIX.matcher(s);if(p.find())return canonical(matchLanguage(p));Matcher end=SUFFIX.matcher(qualityFree(s));return end.find()?canonical(matchLanguage(end)):"";}
 public static String canonical(String value){String s=value==null?"":value.split("[-_]")[0].toUpperCase(Locale.ROOT);if(s.matches("EN|ENG|ENGLISH"))return "en";if(s.matches("NL|NLD|DUTCH"))return "nl";if(s.matches("FR|FRA|FRE|FRENCH"))return "fr";if(s.matches("DE|DEU|GER|GERMAN"))return "de";if(s.matches("ES|SPA|SPANISH"))return "es";if(s.matches("IT|ITA|ITALIAN"))return "it";if(s.matches("PT|POR|PORTUGUESE"))return "pt";if(s.matches("TR|TUR|TURKISH"))return "tr";if(s.matches("RU|RUS|RUSSIAN"))return "ru";if(s.matches("AR|ARA|ARABIC"))return "ar";if(s.matches("PL|POL|POLISH"))return "pl";if(s.matches("HI|HIN|HINDI"))return "hi";return s.toLowerCase(Locale.ROOT);}
 public String languageCode(){return cachedLanguage;}
 public static String languageName(String code){switch(canonical(code)){case "en":return "Engels";case "nl":return "Nederlands";case "fr":return "Frans";case "de":return "Duits";case "es":return "Spaans";case "it":return "Italiaans";case "pt":return "Portugees";case "tr":return "Turks";case "ru":return "Russisch";case "ar":return "Arabisch";case "pl":return "Pools";case "hi":return "Hindi";case "multi":return "Meertalig";case "":case "und":return "Taal niet vermeld";default:return code;}}
 public static Comparator<CatalogEntry> playbackOrder(String preferred){final String code=canonical(preferred);return Comparator.comparingInt((CatalogEntry e)->e.languageCode().equals(code)?0:e.languageCode().equals("en")?1:e.languageCode().equals("multi")?2:e.languageCode().isEmpty()?3:4).thenComparing(Comparator.comparingInt((CatalogEntry e)->e.rank).reversed()).thenComparing(Comparator.comparingLong((CatalogEntry e)->e.added).reversed()).thenComparingInt(e->e.id);}
 public static String normalize(String s){String t=qualityFree(s);t=PREFIX.matcher(t).replaceFirst("");t=SUFFIX.matcher(t).replaceFirst("");t=t.replaceFirst("(?i)^\\s*(?:NF|NETFLIX|AMAZON)\\s*[-|:]\\s*","");return t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
 private static int languageRank(CatalogEntry e){String l=e.languageCode();return l.equals("en")?0:l.equals("multi")?1:l.isEmpty()?2:3;}
 public static final Comparator<CatalogEntry> PLAYBACK_ORDER=Comparator.comparingInt(CatalogEntry::languageRank).thenComparing(Comparator.comparingInt((CatalogEntry e)->e.rank).reversed()).thenComparing(Comparator.comparingLong((CatalogEntry e)->e.added).reversed()).thenComparingInt(e->e.id);
 public static int quality(String s){String x=s.toLowerCase(Locale.ROOT);if(x.matches(".*(2160|\\b4k\\b|\\buhd\\b).*"))return 2160;if(x.matches(".*(1080|\\bfhd\\b).*"))return 1080;if(x.matches(".*(720|\\bhd\\b).*"))return 720;return 0;}
 public static boolean adultCategory(String name){return Pattern.compile("(?:\\bxxx\\b|\\badult\\b|\\bporn(?:ography|o)?\\b|\\berotic(?:a)?\\b|18\\s*\\+|volwassen)",Pattern.CASE_INSENSITIVE).matcher(name==null?"":name).find();}
 public static boolean adultTitle(String name){return Pattern.compile("^\\s*(?:\\[xxx\\]|xxx\\s*\\||18\\s*\\+)",Pattern.CASE_INSENSITIVE).matcher(name==null?"":name).find();}
 public boolean isAdult(){return type.equals("xxx");}public boolean isEpisode(){return type.equals("episode");}
}
