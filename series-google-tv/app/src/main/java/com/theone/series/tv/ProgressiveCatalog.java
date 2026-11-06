package com.theone.series.tv;
import java.util.*;
import java.util.concurrent.*;

/**
 * Each source can stream progressively while the last-known disk/bootstrap
 * catalog remains visible. Incoming previews replace neither the cache nor
 * unrelated sources until that source finishes successfully.
 */
final class ProgressiveCatalog {
 interface Preview { void accept(List<CatalogEntry> entries); }
 interface Source { List<CatalogEntry> load(Preview preview) throws Exception; }
 interface Listener { void update(List<CatalogEntry> entries,boolean complete,int successful); }

 static void load(List<Callable<List<CatalogEntry>>> sources,List<CatalogEntry> cached,Listener listener)throws Exception{
  List<Source> streaming=new ArrayList<>();
  for(Callable<List<CatalogEntry>> source:sources)streaming.add(preview->source.call());
  loadStreaming(streaming,cached,listener);
 }

 static void loadStreaming(List<Source> sources,List<CatalogEntry> cached,Listener listener)throws Exception{
  if(sources.isEmpty())throw new java.io.IOException("No catalog sources");
  List<List<CatalogEntry>> parts=new ArrayList<>(),fresh=new ArrayList<>();
  for(int i=0;i<sources.size();i++){parts.add(new ArrayList<>());fresh.add(Collections.emptyList());}
  for(CatalogEntry e:cached){
   int source=e.id>=1000000000?1:0;
   if(source<parts.size())parts.get(source).add(e);
  }
  if(!cached.isEmpty())listener.update(new ArrayList<>(cached),false,0);
  final Object lock=new Object();
  final int[] previewCount=new int[sources.size()];
  ExecutorService workers=Executors.newFixedThreadPool(sources.size());
  CompletionService<Result> completed=new ExecutorCompletionService<>(workers);
  List<Future<Result>> tasks=new ArrayList<>();
  try{
   for(int i=0;i<sources.size();i++){
    final int source=i;
    tasks.add(completed.submit(()->new Result(source,sources.get(source).load(preview->{
     if(preview==null||preview.isEmpty())return;
     synchronized(lock){
      // Previews are cumulative snapshots, not isolated batches.
      if(preview.size()<=previewCount[source])return;
      previewCount[source]=preview.size();
      fresh.set(source,new ArrayList<>(preview));
      listener.update(mergeVisible(parts,fresh),false,0);
     }
    }))));
   }
   int successful=0;
   for(int i=0;i<sources.size();i++){
    try{
     Result result=completed.take().get();
     synchronized(lock){
      parts.set(result.source,result.entries);
      fresh.set(result.source,Collections.emptyList());
     }
     successful++;
    }catch(ExecutionException ignored){}
    boolean done=i+1==sources.size();
    if(successful>0||done){
     synchronized(lock){listener.update(mergeVisible(parts,fresh),done,successful);}
    }
   }
   if(successful==0&&cached.isEmpty())throw new java.io.IOException("Catalog unavailable");
  }finally{
   for(Future<Result> task:tasks)task.cancel(true);
   workers.shutdownNow();
  }
 }

 /** Retain old items until their source completes; deduplicate IDs while
  * letting newly streamed metadata supersede stale titles and posters. */
 private static List<CatalogEntry> mergeVisible(List<List<CatalogEntry>> parts,List<List<CatalogEntry>> fresh){
  List<CatalogEntry> result=new ArrayList<>();
  for(int source=0;source<parts.size();source++){
   List<CatalogEntry> update=fresh.get(source);
   if(update.isEmpty()){result.addAll(parts.get(source));continue;}
   LinkedHashMap<Integer,CatalogEntry> joined=new LinkedHashMap<>();
   for(CatalogEntry entry:parts.get(source))joined.put(entry.id,entry);
   for(CatalogEntry entry:update)joined.put(entry.id,entry);
   result.addAll(joined.values());
  }
  return result;
 }

 private static final class Result{
  final int source;
  final List<CatalogEntry> entries;
  Result(int source,List<CatalogEntry> entries){this.source=source;this.entries=entries;}
 }
}
