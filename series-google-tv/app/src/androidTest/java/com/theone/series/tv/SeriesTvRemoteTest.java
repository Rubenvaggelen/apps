package com.theone.series.tv;
import android.content.*;import android.view.KeyEvent;import android.widget.GridView;import androidx.test.core.app.ActivityScenario;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;import androidx.test.uiautomator.*;import org.junit.*;import org.junit.runner.RunWith;import java.util.concurrent.atomic.AtomicBoolean;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class) public class SeriesTvRemoteTest {
 @Test public void remoteOpensSeasonsEpisodesAndPlayback()throws Exception{android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageManager().hasSystemFeature("android.software.leanback"));context.getSharedPreferences("the_one_series_tv",0).edit().putString("preferred_language","en").commit();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());Intent i=new Intent(context,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);i.putExtra("series_test_mode",true);try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(i)){
 assertTrue(device.wait(Until.hasObject(By.textContains("THE ONE SERIES")),10000));scenario.onActivity(a->{GridView grid=a.findViewById(1020);grid.requestFocus();grid.setSelection(0);});device.pressDPadRight();device.pressDPadCenter();assertTrue("Remote opens series details",device.wait(Until.hasObject(By.text("Seizoenen en afleveringen")),5000));device.pressDPadCenter();assertTrue("Remote opens seasons",device.wait(Until.hasObject(By.text("Seizoen 1")),5000));device.pressDPadCenter();assertTrue("Remote enters first season",device.wait(Until.hasObject(By.textContains("S01E01")),5000));device.pressDPadCenter();assertTrue("Remote opens episode details",device.wait(Until.hasObject(By.text("▶ Kijk")),5000));device.pressDPadCenter();AtomicBoolean ready=new AtomicBoolean(false);for(int n=0;n<40&&!ready.get();n++){scenario.onActivity(a->ready.set(a.isPlaybackReadyForTest()));Thread.sleep(250);}assertTrue("TV episode renders a native video frame",ready.get());device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE);Thread.sleep(300);scenario.onActivity(a->ready.set(a.isPlaybackReadyForTest()));assertFalse("Remote pauses episode",ready.get());device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY);Thread.sleep(500);scenario.onActivity(a->ready.set(a.isPlaybackReadyForTest()));assertTrue("Remote resumes episode",ready.get());device.pressBack();device.waitForIdle();scenario.onActivity(a->{GridView grid=a.findViewById(1020);grid.requestFocus();grid.setSelection(0);});device.pressDPadUp();device.pressDPadUp();device.pressDPadRight();device.pressDPadCenter();assertTrue("Remote opens language menu",device.wait(Until.hasObject(By.text("Taalvoorkeur")),5000));device.pressDPadDown();device.pressDPadCenter();device.waitForIdle();assertEquals("nl",context.getSharedPreferences("the_one_series_tv",0).getString("preferred_language",""));scenario.onActivity(a->{GridView grid=a.findViewById(1020);grid.requestFocus();grid.setSelection(0);});device.pressDPadUp();device.pressDPadUp();device.pressDPadRight();device.pressDPadRight();device.pressDPadCenter();assertTrue("Remote reopens season choice",device.wait(Until.hasObject(By.text("Seizoen 2")),5000));device.pressDPadDown();device.pressDPadCenter();assertTrue("Remote chooses second season",device.wait(Until.hasObject(By.textContains("S02E01")),5000));
 }finally{context.getSharedPreferences("the_one_series_tv",0).edit().putString("preferred_language","en").commit();}}
 @Test public void overlayDisappearsFiveSecondsAfterLastRemoteInputEvenWithFocusedButton()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  Intent intent=new Intent(context,MainActivity.class).putExtra("series_test_mode",true);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
   scenario.onActivity(a->{
    try{
     java.lang.reflect.Method start=MainActivity.class.getDeclaredMethod("startPlayback",CatalogEntry.class,long.class);
     start.setAccessible(true);
     start.invoke(a,new CatalogEntry(999,"Overlay test","mp4","","EN","series",0L),0L);
     assertEquals(android.view.View.VISIBLE,a.findViewById(1048).getVisibility());
     assertTrue(a.findViewById(1041).requestFocus());
    }catch(Exception e){throw new RuntimeException(e);}
   });
   Thread.sleep(5500);
   scenario.onActivity(a->{
    assertEquals("Top menu hides even while focused",android.view.View.GONE,a.findViewById(1048).getVisibility());
    assertFalse("Focus must move off hidden controls",a.findViewById(1041).hasFocus());
   });
   UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
   device.pressDPadUp();
   scenario.onActivity(a->assertEquals("Remote reveals menu",android.view.View.VISIBLE,a.findViewById(1048).getVisibility()));
   device.pressDPadRight();
   Thread.sleep(3000);
   scenario.onActivity(a->assertEquals("Interaction resets inactivity clock",android.view.View.VISIBLE,a.findViewById(1048).getVisibility()));
   Thread.sleep(2600);
   scenario.onActivity(a->assertEquals("Menu hides after another five idle seconds",android.view.View.GONE,a.findViewById(1048).getVisibility()));
  }
 }

 @Test public void movieCatalogIsNotExposed()throws Exception{MediaRepository r=new MediaRepository(InstrumentationRegistry.getInstrumentation().getTargetContext());try{r.load("movies");fail("Only series are allowed");}catch(java.io.IOException expected){}}
}
