package com.theone.cinema.tv;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.C;
import org.junit.*;import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class) public class SubtitlePlaybackTest {
 private static ExoPlayer player(MainActivity a){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("player");f.setAccessible(true);return (ExoPlayer)f.get(a);}catch(Exception e){throw new RuntimeException(e);}}
 @Test public void subtitlesRenderAndCanBeDisabled()throws Exception{
  Intent i=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class).putExtra("cinema_test_mode",true).putExtra("subtitle_test_mode",true);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(i)){
   scenario.onActivity(a->{try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("prefs");f.setAccessible(true);((android.content.SharedPreferences)f.get(a)).edit().putBoolean("subtitles_off",false).commit();java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("startPlayback",CatalogEntry.class,long.class);m.setAccessible(true);m.invoke(a,new CatalogEntry(999,"Subtitle test","mp4","","EN", "movies",0L),0L);}catch(Exception e){throw new RuntimeException(e);}});
   AtomicBoolean rendered=new AtomicBoolean(false);
   for(int n=0;n<80&&!rendered.get();n++){scenario.onActivity(a->{ExoPlayer p=player(a);rendered.set(p!=null&&p.getCurrentCues().cues.stream().anyMatch(c->c.text!=null&&c.text.toString().contains("Ondertiteling werkt")));});Thread.sleep(100);}
   assertTrue("Actual Dutch subtitle cues are rendered",rendered.get());
   scenario.onActivity(a->a.findViewById(1045).performClick());
   UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
   assertTrue(device.wait(Until.hasObject(By.text("Uit")),3000));device.findObject(By.text("Uit")).click();
   scenario.onActivity(a->assertTrue(player(a).getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)));
   scenario.onActivity(a->a.findViewById(1045).performClick());
   assertTrue(device.wait(Until.hasObject(By.text("Automatisch (Nederlands, daarna Engels)")),3000));device.findObject(By.text("Automatisch (Nederlands, daarna Engels)")).click();
   scenario.onActivity(a->assertFalse(player(a).getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)));
  }
 }
}
