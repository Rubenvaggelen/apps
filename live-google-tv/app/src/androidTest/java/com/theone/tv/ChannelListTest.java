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
}
