package com.theone.tv.phone;
import java.util.*;
final class LanguageSelection {
 static List<String> languages(Collection<List<CatalogEntry>> groups){LinkedHashSet<String> codes=new LinkedHashSet<>();codes.add("en");for(List<CatalogEntry> xs:groups)for(CatalogEntry e:xs){String code=e.languageCode();if(!code.isEmpty()&&!code.equals("und"))codes.add(code);}return Collections.unmodifiableList(new ArrayList<>(codes));}
 static <K> Map<K,List<CatalogEntry>> choose(Map<K,List<CatalogEntry>> catalogs,Map<String,List<CatalogEntry>> variants,String language){Comparator<CatalogEntry> order=CatalogEntry.playbackOrder(language);Map<K,List<CatalogEntry>> result=new LinkedHashMap<>();for(Map.Entry<K,List<CatalogEntry>> catalog:catalogs.entrySet()){List<CatalogEntry> selected=new ArrayList<>(catalog.getValue().size());for(CatalogEntry entry:catalog.getValue()){List<CatalogEntry> versions=variants.get(entry.key());CatalogEntry best=entry;if(versions!=null&&!versions.isEmpty()){best=versions.get(0);for(int i=1;i<versions.size();i++)if(order.compare(versions.get(i),best)<0)best=versions.get(i);}selected.add(best);}result.put(catalog.getKey(),selected);}return result;}
}
