package com.theone.tv;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class CatalogPerformanceTest {
 private CatalogEntry entry(int id){return new CatalogEntry(id,"NL - Test "+id,"m3u8","","NL","live",0);}
 @Test public void coldStartEmitsSeveralPreviewsAndThenFinal()throws Exception{
  List<Integer> sizes=new ArrayList<>();List<Boolean> completed=new ArrayList<>();
  ProgressiveCatalog.loadStreaming(Collections.singletonList(preview->{
   preview.accept(Arrays.asList(entry(1),entry(2)));
   preview.accept(Arrays.asList(entry(1),entry(2),entry(3)));
   return Arrays.asList(entry(1),entry(2),entry(3),entry(4));
  }),Collections.emptyList(),(rows,complete,successful)->{sizes.add(rows.size());completed.add(complete);});
  assertEquals(Arrays.asList(2,3,4),sizes);
  assertEquals(Arrays.asList(false,false,true),completed);
 }
 @Test public void warmCacheRemainsVisibleUntilReplacementFinishes()throws Exception{
  List<Integer> ids=new ArrayList<>();AtomicInteger updates=new AtomicInteger();
  ProgressiveCatalog.loadStreaming(Collections.singletonList(preview->{
   preview.accept(Collections.singletonList(entry(2)));
   return Collections.singletonList(entry(3));
  }),Collections.singletonList(entry(1)),(rows,complete,successful)->{updates.incrementAndGet();ids.add(rows.get(0).id);});
  assertEquals(Arrays.asList(1,3),ids);assertEquals(2,updates.get());
 }
 @Test public void failedSourceRetainsCacheWhileHealthySourceRefreshes()throws Exception{
  List<CatalogEntry> warm=Arrays.asList(entry(1),entry(1000000002));
  List<CatalogEntry> last=new ArrayList<>();AtomicInteger successes=new AtomicInteger(-1);
  ProgressiveCatalog.loadStreaming(Arrays.asList(
   preview->{throw new java.io.IOException("offline");},
   preview->Collections.singletonList(entry(1000000003))
  ),warm,(rows,complete,successful)->{if(complete){last.clear();last.addAll(rows);successes.set(successful);}});
  assertEquals(1,successes.get());assertEquals(2,last.size());
  assertEquals(1,last.get(0).id);assertEquals(1000000003,last.get(1).id);
 }
}
