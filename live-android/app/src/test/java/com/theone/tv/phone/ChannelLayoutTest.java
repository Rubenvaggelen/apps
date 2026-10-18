package com.theone.tv.phone;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ChannelLayoutTest {
    private CatalogEntry entry(int id,String name,String hint){
        return new CatalogEntry(id,name,"ts","","","live",0);
    }
    private CatalogEntry channel(int id,String name,String hint){
        return new CatalogEntry(id,name,"ts","",hint,"live",0);
    }
    @Test public void countrySelectorMatchesMediaPlayerOrder(){
        assertArrayEquals(new String[]{"Nederland","België","Duitsland","Verenigd Koninkrijk",
                "Verenigde Staten","Frankrijk","Turkije","Spanje","Italië","Portugal",
                "Suriname","Overige","Alle landen"},ChannelLayout.NAMES);
    }
    @Test public void nlCountryOnlyContainsExplicitNlChannelPrefixes(){
        List<CatalogEntry> entries=Arrays.asList(
            channel(1,"NL - NPO 1 HD","Nederland"),
            channel(2,"NL - RTL 4","Nederland"),
            channel(3,"NPO 2","Nederland"),
            channel(4,"NLD - Test","Nederland"),
            channel(5,"US | CNN","Verenigde Staten")
        );
        ChannelLayout.Layout layout=ChannelLayout.prepare(entries,Collections.emptyMap());
        assertEquals(2,layout.countries.get("NL").size());
        assertEquals("NL - NPO 1 HD",layout.countries.get("NL").get(0).title);
        assertEquals("NL - RTL 4",layout.countries.get("NL").get(1).title);
        assertEquals(5,layout.countries.get("ALL").size());
    }
    @Test public void repeatedProviderChannelHasOneTileAndBothPlaybackSources(){
        CatalogEntry primary=channel(81,"NL - NPO 1 HD","Nederland");
        CatalogEntry secondary=channel(1000000081,"NL | NPO 1 FHD","Nederland");
        ChannelLayout.Layout view=ChannelLayout.prepare(Arrays.asList(primary,secondary),Collections.emptyMap());
        assertEquals(1,view.countries.get("NL").size());
        assertEquals(2,view.alternativeStreams.get(view.countries.get("NL").get(0).key()).size());
    }
    @Test public void mediaPlayerQualityTierSortsAheadOfZiggoNumbers(){
        List<CatalogEntry> channels=Arrays.asList(
            channel(91,"NL - RTL 4","Nederland"),
            channel(92,"NL - NPO 1 4K","Nederland"),
            channel(93,"NL - NPO 2","Nederland")
        );
        List<CatalogEntry> displayed=ChannelLayout.prepare(channels,Collections.emptyMap()).countries.get("NL");
        assertEquals("NL - NPO 2",displayed.get(0).title);
        assertEquals("NL - RTL 4",displayed.get(1).title);
        assertEquals("NL - NPO 1 4K",displayed.get(2).title);
    }
    @Test public void explicitNlPrefixSurvivesEarlyProviderDedup(){
        CatalogEntry withoutPrefix=channel(100,"NPO 1","Nederland");
        CatalogEntry withPrefix=channel(101,"NL - NPO 1 HD","Nederland");
        java.util.Map<String,java.util.List<CatalogEntry>> grouped=CatalogEntry.group(
            Arrays.asList(withoutPrefix,withPrefix));
        // CatalogEntry.group can select the unprefixed title. The country view
        // must work on the original provider items like Media Player does.
        ChannelLayout.Layout layout=ChannelLayout.prepare(Arrays.asList(withoutPrefix,withPrefix),grouped);
        assertEquals(1,layout.countries.get("NL").size());
        assertEquals("NL - NPO 1 HD",layout.countries.get("NL").get(0).title);
    }
    @Test public void alternateFormatsAndSourcesAreStillPlayable(){
        CatalogEntry hls=new CatalogEntry(1,"NL - NPO 2","m3u8","","Nederland","live",0);
        CatalogEntry ts=new CatalogEntry(1,"NL - NPO 2","ts","","Nederland","live",0);
        CatalogEntry backup=new CatalogEntry(1000000002,"NL - NPO 2 HD","ts","","Nederland","live",0);
        ChannelLayout.Layout layout=ChannelLayout.prepare(Arrays.asList(hls,ts,backup),Collections.emptyMap());
        assertEquals(1,layout.countries.get("NL").size());
        assertEquals(3,layout.alternativeStreams.get(layout.countries.get("NL").get(0).key()).size());
    }
    @Test public void prefixesFollowMediaPlayerBoundaries(){
        assertTrue(ChannelLayout.hasNlPrefix("NL | SBS 6"));
        assertTrue(ChannelLayout.hasNlPrefix("nl-RTL 4"));
        assertFalse(ChannelLayout.hasNlPrefix("NLD - Channel"));
        assertFalse(ChannelLayout.hasNlPrefix("NPO 1"));
    }
}
