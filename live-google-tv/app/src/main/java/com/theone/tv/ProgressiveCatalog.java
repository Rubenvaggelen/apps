package com.theone.tv;
import java.util.*;import java.util.concurrent.*;
final class ProgressiveCatalog {
 interface Preview {void accept(List<CatalogEntry> entries);}
 interface Source {List<CatalogEntry> load(Preview preview)throws Exception;}
 interface Listener {void update(List<CatalogEntry> entries,boolean complete,int successful);}
 static void load(List<Callable<List<CatalogEntry>>> sources,List<CatalogEntry> cached,Listener listener)throws Exception{List<Source> streaming=new ArrayList<>();for(Callable<List<CatalogEntry>> source:sources)streaming.add(preview->source.call());loadStreaming(streaming,cached,listener);}
 static void loadStreaming(List<Source> sources,List<CatalogEntry> cached,Listener listener)throws Exception{
  List<List<CatalogEntry>> parts=new ArrayList<>();for(int i=0;i<sources.size();i++)parts.add(new ArrayList<>());
  for(CatalogEntry e:cached){int source=e.id>=1000000000?1:0;if(source<parts.size())parts.get(source).add(e);}
  boolean[] cachedSource=new boolean[parts.size()];int[] previewSizes=new int[parts.size()];for(int i=0;i<parts.size();i++)cachedSource[i]=!parts.get(i).isEmpty();
  if(!cached.isEmpty())listener.update(new ArrayList<>(cached),false,0);
  Object lock=new Object();
  ExecutorService workers=Executors.newFixedThreadPool(sources.size());CompletionService<Result> completed=new ExecutorCompletionService<>(workers);List<Future<Result>> tasks=new ArrayList<>();
  try{for(int i=0;i<sources.size();i++){final int source=i;tasks.add(completed.submit(()->new Result(source,sources.get(source).load(preview->{synchronized(lock){if(!cachedSource[source]&&preview.size()>previewSizes[source]){previewSizes[source]=preview.size();parts.set(source,new ArrayList<>(preview));listener.update(merge(parts),false,0);}}}))));}
   int successful=0;
   for(int i=0;i<sources.size();i++){try{Result result=completed.take().get();synchronized(lock){parts.set(result.source,result.entries);}successful++;}catch(ExecutionException ignored){}
    boolean done=i+1==sources.size();if(successful>0||done){synchronized(lock){listener.update(merge(parts),done,successful);}}
   }
   if(successful==0&&cached.isEmpty())throw new java.io.IOException("Catalog unavailable");
  }finally{for(Future<Result> task:tasks)task.cancel(true);workers.shutdownNow();}
 }
 private static List<CatalogEntry> merge(List<List<CatalogEntry>> parts){List<CatalogEntry> result=new ArrayList<>();for(List<CatalogEntry> part:parts)result.addAll(part);return result;}
 private static final class Result {final int source;final List<CatalogEntry> entries;Result(int source,List<CatalogEntry> entries){this.source=source;this.entries=entries;}}
}
