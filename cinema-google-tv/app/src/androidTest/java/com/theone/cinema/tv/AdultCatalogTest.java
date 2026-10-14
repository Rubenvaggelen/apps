package com.theone.cinema.tv;
import android.content.Intent;import androidx.test.core.app.ActivityScenario;import androidx.test.platform.app.InstrumentationRegistry;import org.junit.Test;import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;import static org.junit.Assert.*;
public class AdultCatalogTest {
 @Test public void switchingToAdultCancelsSlowMovieLoad()throws Exception{
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Intent i=new Intent(c,MainActivity.class).putExtra("cinema_test_mode",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  CountDownLatch started=new CountDownLatch(1),cancelled=new CountDownLatch(1);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(i)){scenario.onActivity(a->a.beginSlowMovieLoadForTest(started,cancelled));assertTrue(started.await(3,TimeUnit.SECONDS));scenario.onActivity(a->{a.findViewById(1006).performClick();assertEquals(1,((android.widget.GridView)a.findViewById(1020)).getCount());});assertTrue("XXX must not wait for the film catalog",cancelled.await(2,TimeUnit.SECONDS));}
 }
 @Test public void adultCategoryUsesSmallScopedRequestAndEarlyPreview()throws Exception{
  ExecutorService server=Executors.newSingleThreadExecutor();List<String> paths=new CopyOnWriteArrayList<>();
  try(ServerSocket socket=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))){
   Future<?> served=server.submit(()->{try{for(int request=0;request<2;request++)try(Socket client=socket.accept()){
    BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));String first=in.readLine();paths.add(first);String line;while((line=in.readLine())!=null&&!line.isEmpty()){}
    String body=first.contains("get_vod_categories")?"[{\"category_id\":\"7\",\"category_name\":\"XXX\"}]":"[{\"stream_id\":17,\"name\":\"Separate test title\",\"category_id\":\"7\"}]";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
    OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();
   }}catch(Exception e){throw new RuntimeException(e);}});
   MediaRepository repo=new MediaRepository(InstrumentationRegistry.getInstrumentation().getTargetContext());repo.useLocalSourceForTest("http://127.0.0.1:"+socket.getLocalPort());List<CatalogEntry> finalRows=new ArrayList<>();java.util.concurrent.atomic.AtomicBoolean early=new java.util.concurrent.atomic.AtomicBoolean();
   repo.load("xxx",(rows,done,success)->{if(!done&&!rows.isEmpty())early.set(true);if(done){finalRows.clear();finalRows.addAll(rows);}});
   served.get(5,TimeUnit.SECONDS);assertEquals(1,finalRows.size());assertTrue(finalRows.get(0).isAdult());assertTrue(early.get());assertTrue(paths.get(1).contains("category_id=7"));
  }finally{server.shutdownNow();}
 }
}
