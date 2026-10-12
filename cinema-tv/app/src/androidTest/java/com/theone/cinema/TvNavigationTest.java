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
 @Test public void launcherSearchAndRemoteDetailsWork()throws Exception {
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
   device.findObject(By.text("Mijn lijst")).click();
   assertTrue("Favorites start empty",device.wait(Until.hasObject(By.text("Geen titels gevonden.")),5000));
   device.findObject(By.text("Films")).click();device.waitForIdle();
   device.pressDPadDown();device.pressDPadDown();device.pressDPadDown();device.pressDPadRight();device.pressDPadCenter();
   assertTrue("Remote opens film details",device.wait(Until.hasObject(By.text("▶ Kijk")),5000));
   device.pressBack();
  }
 }
}
