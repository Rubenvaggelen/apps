package com.theone.tv;
import android.content.Intent;import android.widget.*;import android.util.JsonReader;import java.io.StringReader;
import androidx.test.core.app.ActivityScenario;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;import org.junit.runner.RunWith;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class) public class ChannelListTest {
 @Test public void completeChannelListHasNoPaginationLimit()throws Exception{
 Intent i=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class).putExtra("tv_test_mode",true).putExtra("tv_test_count",600);
 try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(i)){scenario.onActivity(a->{ListView list=a.findViewById(1020);assertEquals(600,list.getAdapter().getCount());assertEquals(android.view.View.GONE,a.findViewById(1030).getVisibility());CatalogEntry last=(CatalogEntry)list.getAdapter().getItem(599);assertEquals("Testkanaal 600",last.displayTitle());list.setSelection(599);assertTrue(list.getAdapter().getView(599,null,list).getContentDescription().toString().contains("600"));});}
 }
 @Test public void categoryArraysDoNotAbortProviderCatalog()throws Exception{java.lang.reflect.Method m=MediaRepository.class.getDeclaredMethod("stringValue",JsonReader.class);m.setAccessible(true);try(JsonReader r=new JsonReader(new StringReader("[12,\"14\",null]"))){assertEquals("12,14",m.invoke(null,r));}}
 @Test public void normalChannelsSortBeforeInactiveEventRows(){CatalogEntry event=new CatalogEntry(1,"NL - NO EVENT STREAMING","ts","","NL","live",0);CatalogEntry channel=new CatalogEntry(2,"NL - NPO 1 HD","ts","","NL","live",0);assertTrue(CatalogEntry.CHANNEL_ORDER.compare(channel,event)<0);assertEquals("NPO 1",channel.displayTitle());}

 @Test public void xxxBackRestoresSearchAndPreviousChannelList()throws Exception{
  Intent i=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class)
      .putExtra("tv_test_mode",true).putExtra("tv_test_count",600);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(i)){
   scenario.onActivity(a->{
    try{
     java.lang.reflect.Field verified=MainActivity.class.getDeclaredField("adultConfirmed");
     verified.setAccessible(true);
     verified.setBoolean(a,true); // Test-only bypass; the real app always requests 18+ confirmation.
    }catch(Exception failure){throw new AssertionError(failure);}
    EditText search=a.findViewById(1010);
    search.setText("Testkanaal 25");
    a.findViewById(1011).performClick();
    ListView channels=a.findViewById(1020);
    assertTrue(channels.getAdapter().getCount()>0);
    a.findViewById(1008).performClick();
    assertNotNull("XXX must show a visible Back control",a.findViewById(1009));
    a.findViewById(1009).performClick();
    EditText restored=a.findViewById(1010);
    assertEquals("Testkanaal 25",restored.getText().toString());
    ListView returned=a.findViewById(1020);
    assertTrue("Previous filtered channel list restored",returned.getAdapter().getCount()>0);
    assertNull("XXX-only Back control must disappear on return",a.findViewById(1009));
   });
  }
 }
 @Test public void xxxBackRestoresScrollPositionOnRemoteOrPhone()throws Exception{
  Intent i=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class)
      .putExtra("tv_test_mode",true).putExtra("tv_test_count",600);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(i)){
   scenario.onActivity(a->{
    try{
     java.lang.reflect.Field verified=MainActivity.class.getDeclaredField("adultConfirmed");
     verified.setAccessible(true);
     verified.setBoolean(a,true);
    }catch(Exception failure){throw new AssertionError(failure);}
    ((ListView)a.findViewById(1020)).setSelectionFromTop(240,0);
   });
   final int[] index={0};
   scenario.onActivity(a->{
     ListView list=a.findViewById(1020);
     index[0]=list.getFirstVisiblePosition();
     assertTrue("Test list scrolled before entering XXX",index[0]>0);
     a.findViewById(1008).performClick();
     a.onBackPressed(); // Real remote Back uses the identical return point.
   });
   scenario.onActivity(a->{
     ListView list=a.findViewById(1020);
     assertEquals("Back must return to the same channel row",index[0],list.getFirstVisiblePosition());
   });
  }
 }


 @Test public void googleTvRemoteCanFocusXxxBetweenKanalenAndLanden() throws Exception {
  Intent i=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class)
      .putExtra("tv_test_mode",true).putExtra("tv_test_count",40);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(i)){
   scenario.onActivity(a->{
     assertTrue(a.findViewById(1001).requestFocus());
     assertEquals(1001,a.getCurrentFocus().getId());
     a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
     assertNotNull("XXX should be focusable on Google TV",a.getCurrentFocus());
     assertEquals("Right from Kanalen must reach XXX",1008,a.getCurrentFocus().getId());
     a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
     assertEquals("Right from XXX must reach Landen",1007,a.getCurrentFocus().getId());
     a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_LEFT));
     assertEquals("Left from Landen must return to XXX",1008,a.getCurrentFocus().getId());
     assertTrue("XXX must respond to click",a.findViewById(1008).performClick());
     assertNull("18+ must be confirmed before opening XXX",a.findViewById(1009));
   });
  }
 }
 @Test public void sonyArchiveNullSigningInfoDoesNotCauseNullPointer() {
   android.content.pm.PackageInfo legacy=new android.content.pm.PackageInfo();
   android.content.pm.Signature cert=new android.content.pm.Signature("3006020101");
   legacy.signatures=new android.content.pm.Signature[]{cert};
   legacy.signingInfo=null;
   assertArrayEquals("Legacy signed APK has usable certificate even with null SigningInfo",
       new android.content.pm.Signature[]{cert},FamilyUpdateNotice.signaturesOf(legacy));
   assertNull(FamilyUpdateNotice.signaturesOf(null));
   android.content.pm.PackageInfo noCert=new android.content.pm.PackageInfo();
   assertNull("Missing vendor certificate must not throw",FamilyUpdateNotice.signaturesOf(noCert));
 }

}
