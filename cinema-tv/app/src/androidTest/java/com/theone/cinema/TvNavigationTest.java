package com.theone.cinema;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class) public class TvNavigationTest {
 @Test public void relayTokenNeverGoesToSecondProvider()throws Exception{MediaRepository r=new MediaRepository(InstrumentationRegistry.getInstrumentation().getTargetContext());assertTrue(r.headersForUri("http://cqqwnnig.bulidns.com/movie/test").isEmpty());assertEquals(1,r.headersForUri("https://rubenvanaggelen.com/the-one-remote-api/cinema.php?action=stream").size());}
 @Test public void phoneSearchPlaybackAndSeparateFolderWork()throws Exception {
  UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
  Intent i=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class);
  i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);i.putExtra("cinema_test_mode",true);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(i)) {
   assertTrue("Cinema launcher is visible",device.wait(Until.hasObject(By.textContains("THE ONE CINEMA")),10000));
   UiObject2 search=device.findObject(By.clazz("android.widget.EditText"));assertNotNull("Search field exists",search);
   search.setText("testfilm 3");device.waitForIdle();
   assertTrue("Film search returns fixture",device.wait(Until.hasObject(By.text("Cinema testfilm 3")),5000));
   device.findObject(By.text("Cinema testfilm 3")).click();
   assertTrue("Kijk action exists",device.wait(Until.hasObject(By.text("▶ Kijk")),5000));
   device.findObject(By.text("▶ Kijk")).click();
   AtomicBoolean playing=new AtomicBoolean(false);
   for(int n=0;n<40&&!playing.get();n++){scenario.onActivity(a->playing.set(a.isPlaybackReadyForTest()));Thread.sleep(250);}
   assertTrue("Native player rendered a video frame and is playing",playing.get());
   device.pressBack();device.waitForIdle();
   scenario.onActivity(a->a.findViewById(1003).performClick());
   assertTrue("Favorites start empty",device.wait(Until.hasObject(By.text("Geen titels gevonden.")),5000));
   scenario.onActivity(a->a.findViewById(1006).performClick());
   assertTrue("Separate folder has its fixture",device.wait(Until.hasObject(By.text("Afzonderlijke testtitel")),5000));
   assertNull("Regular titles stay outside the separate folder",device.findObject(By.text("Cinema testfilm 1")));
   scenario.onActivity(a->a.findViewById(1001).performClick());device.waitForIdle();
   assertNull("Separate title stays outside Films",device.findObject(By.text("Afzonderlijke testtitel")));
   device.findObject(By.text("Cinema testfilm 1")).click();
   assertTrue("Touch opens film details",device.wait(Until.hasObject(By.text("▶ Kijk")),5000));
   device.pressBack();
  }
 }
 @Test public void separateFolderLoadsAlternativeThumbnail()throws Exception {
  android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(16,24,android.graphics.Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.CYAN);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);image.recycle();byte[] png=bytes.toByteArray();
  try(java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))){
   Thread worker=new Thread(()->{while(!server.isClosed())try(java.net.Socket client=server.accept()){java.io.BufferedReader in=new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream()));String request=in.readLine();for(String line;(line=in.readLine())!=null&&!line.isEmpty();){}boolean cover=request!=null&&request.contains("/cover ");byte[] body=cover?png:new byte[0];String headers="HTTP/1.1 "+(cover?"200 OK":"404 Not Found")+"\r\nContent-Type: image/png\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";client.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));client.getOutputStream().write(body);client.getOutputStream().flush();}catch(java.io.IOException ignored){}});worker.setDaemon(true);worker.start();
   String root="http://127.0.0.1:"+server.getLocalPort();Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);intent.putExtra("cinema_test_mode",true);intent.putExtra("cinema_test_poster_root",root);
   try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){scenario.onActivity(a->a.findViewById(1006).performClick());AtomicBoolean loaded=new AtomicBoolean(false);for(int n=0;n<60&&!loaded.get();n++){scenario.onActivity(a->loaded.set(a.hasPosterForTest(root+"/cover")));Thread.sleep(100);}assertTrue("Separate folder decodes alternative cover when first link is broken",loaded.get());}
  }
 }

 @Test public void languagePreferenceIsSelectableAndPersisted()throws Exception{android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.getSharedPreferences("the_one_cinema",android.content.Context.MODE_PRIVATE).edit().putString("preferred_language","en").commit();Intent intent=new Intent(context,MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);intent.putExtra("cinema_test_mode",true);UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){scenario.onActivity(a->a.findViewById(1007).performClick());assertTrue(device.wait(Until.hasObject(By.text("Engels · standaard")),5000));assertTrue(device.wait(Until.hasObject(By.text("Nederlands")),5000));device.findObject(By.text("Nederlands")).click();assertTrue(device.wait(Until.hasObject(By.text("Cinema testfilm 1 NL")),5000));assertEquals("nl",context.getSharedPreferences("the_one_cinema",android.content.Context.MODE_PRIVATE).getString("preferred_language",""));}finally{context.getSharedPreferences("the_one_cinema",android.content.Context.MODE_PRIVATE).edit().putString("preferred_language","en").commit();}}

}
