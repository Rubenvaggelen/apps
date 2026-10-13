package com.theone.cinema;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;import java.util.concurrent.*;import java.io.*;
public class CatalogLoadingTest {
 private CatalogEntry entry(int id,String name,String type){return new CatalogEntry(id,name,"mp4","https://example.org/poster.jpg","7",type,123,"42");}
 @Test public void streamingPreviewAppearsBeforeProviderFinishes()throws Exception{
  CountDownLatch release=new CountDownLatch(1),previewReady=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();List<List<CatalogEntry>> snapshots=new CopyOnWriteArrayList<>();
  Future<?> task=worker.submit(()->{try{ProgressiveCatalog.loadStreaming(Collections.singletonList(preview->{preview.accept(Collections.singletonList(entry(1,"EN - Film","movies")));if(!release.await(5,TimeUnit.SECONDS))throw new IOException();return Arrays.asList(entry(1,"EN - Film","movies"),entry(2,"EN - Other","movies"));}),Collections.emptyList(),(items,done,success)->{snapshots.add(items);if(!done)previewReady.countDown();});}catch(Exception e){throw new RuntimeException(e);}});
  try{assertTrue(previewReady.await(3,TimeUnit.SECONDS));assertFalse(task.isDone());assertEquals(1,snapshots.get(0).size());release.countDown();task.get(3,TimeUnit.SECONDS);assertEquals(2,snapshots.get(snapshots.size()-1).size());}finally{release.countDown();worker.shutdownNow();}
 }
 @Test public void previewCannotReplaceCompleteCachedSource()throws Exception{
  List<CatalogEntry> cached=Arrays.asList(entry(1,"EN - Film","movies"),entry(2,"EN - Other","movies"));List<List<CatalogEntry>> snapshots=new ArrayList<>();ProgressiveCatalog.loadStreaming(Collections.singletonList(preview->{preview.accept(Collections.singletonList(cached.get(0)));return cached;}),cached,(items,done,success)->snapshots.add(items));for(List<CatalogEntry> items:snapshots)assertEquals(2,items.size());
 }
 @Test public void firstSourceAppearsBeforeSlowSourceCompletes()throws Exception {
  CountDownLatch release=new CountDownLatch(1),first=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();List<List<CatalogEntry>> snapshots=new CopyOnWriteArrayList<>();
  Future<?> task=worker.submit(()->{try{ProgressiveCatalog.load(Arrays.asList(()->Collections.singletonList(entry(1,"EN - Film","movies")),()->{if(!release.await(5,TimeUnit.SECONDS))throw new IOException();return Collections.singletonList(entry(1000000002,"NL - Film","movies"));}),Collections.emptyList(),(items,done,success)->{snapshots.add(items);if(!done)first.countDown();});}catch(Exception e){throw new RuntimeException(e);}});
  try{assertTrue(first.await(3,TimeUnit.SECONDS));assertFalse(task.isDone());assertEquals(1,snapshots.get(0).size());release.countDown();task.get(3,TimeUnit.SECONDS);assertEquals(2,snapshots.get(snapshots.size()-1).size());assertEquals(1,CatalogEntry.group(snapshots.get(snapshots.size()-1)).size());}finally{release.countDown();worker.shutdownNow();}
 }
 @Test public void cacheIsPublishedBeforeNetworkAndFailedSourceIsRetained()throws Exception{
  List<List<CatalogEntry>> snapshots=new ArrayList<>();CatalogEntry old=entry(1000000002,"NL - Film","movies");
  ProgressiveCatalog.load(Arrays.asList(()->Collections.singletonList(entry(1,"EN - Film","movies")),()->{throw new IOException();}),Collections.singletonList(old),(items,done,success)->snapshots.add(items));
  assertSame(old,snapshots.get(0).get(0));List<CatalogEntry> last=snapshots.get(snapshots.size()-1);assertEquals(2,last.size());assertTrue(last.contains(old));
 }
 @Test public void successfulRefreshReplacesOldSourceRatherThanAccumulating()throws Exception{
  List<List<CatalogEntry>> snapshots=new ArrayList<>();ProgressiveCatalog.load(Collections.singletonList(()->Collections.singletonList(entry(2,"EN - New","movies"))),Collections.singletonList(entry(1,"EN - Old","movies")),(items,done,success)->snapshots.add(items));
  List<CatalogEntry> last=snapshots.get(snapshots.size()-1);assertEquals(1,last.size());assertEquals(2,last.get(0).id);
 }
 @Test public void cacheRoundTripKeepsAllVariantsAndAdultSeparation()throws Exception{
  List<CatalogEntry> entries=Arrays.asList(entry(1,"EN - Film","movies"),entry(1000000002,"NL - Film","movies"));ByteArrayOutputStream bytes=new ByteArrayOutputStream();CatalogCodec.write(bytes,entries);List<CatalogEntry> restored=CatalogCodec.read(new ByteArrayInputStream(bytes.toByteArray()),"movies");assertEquals(2,restored.size());assertEquals("en",restored.get(0).languageCode());assertEquals("nl",restored.get(1).languageCode());assertEquals("42",restored.get(0).movieId);assertEquals(entries.get(1).poster,restored.get(1).poster);try{CatalogCodec.read(new ByteArrayInputStream(bytes.toByteArray()),"xxx");fail("Movie cache cannot populate adult folder");}catch(IOException expected){}
 }
 @Test public void truncatedCacheIsRejected()throws Exception{try{CatalogCodec.read(new ByteArrayInputStream(new byte[]{1,2,3}),"movies");fail();}catch(IOException expected){}}
 @Test public void failedRefreshStillReturnsOfflineCache()throws Exception{List<CatalogEntry> old=Collections.singletonList(entry(1,"EN - Film","movies"));List<Boolean> done=new ArrayList<>();ProgressiveCatalog.load(Collections.singletonList(()->{throw new IOException();}),old,(items,complete,success)->{assertEquals(1,items.size());done.add(complete);});assertEquals(Arrays.asList(false,true),done);}
}
