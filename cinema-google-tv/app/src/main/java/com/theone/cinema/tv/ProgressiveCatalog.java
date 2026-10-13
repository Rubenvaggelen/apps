package com.theone.cinema.tv;
import java.util.*;import java.util.concurrent.*;
final class ProgressiveCatalog {
 interface Listener {void update(List<CatalogEntry> entries,boolean complete,int successful);}
 static void load(List<Callable<List<CatalogEntry>>> sources,List<CatalogEntry> cached,Listener listener)throws Exception{
  List<List<CatalogEntry>> parts=new ArrayList<>();for(int i=0;i<sources.size();i++)parts.add(new ArrayList<>());
  for(CatalogEntry e:cached){int source=e.id>=1000000000?1:0;if(source<parts.size())parts.get(source).add(e);}
  if(!cached.isEmpty())listener.update(new ArrayList<>(cached),false,0);
  ExecutorService workers=Executors.newFixedThreadPool(sources.size());CompletionService<Result> completed=new ExecutorCompletionService<>(workers);List<Future<Result>> tasks=new ArrayList<>();
  try{for(int i=0;i<sources.size();i++){final int source=i;tasks.add(completed.submit(()->new Result(source,sources.get(source).call())));}
   int successful=0;
   for(int i=0;i<sources.size();i++){try{Result result=completed.take().get();parts.set(result.source,result.entries);successful++;}catch(ExecutionException ignored){}
    boolean done=i+1==sources.size();if(successful>0||done){List<CatalogEntry> merged=new ArrayList<>();for(List<CatalogEntry> part:parts)merged.addAll(part);listener.update(merged,done,successful);}
   }
   if(successful==0&&cached.isEmpty())throw new java.io.IOException("Catalog unavailable");
  }finally{for(Future<Result> task:tasks)task.cancel(true);workers.shutdownNow();}
 }
 private static final class Result {final int source;final List<CatalogEntry> entries;Result(int source,List<CatalogEntry> entries){this.source=source;this.entries=entries;}}
}
