package com.theone.mediaplayer;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelGuideTest {
    @Test public void dutchChannelsFollowZiggoTopOrder() {
        assertTrue(ChannelGuide.ziggoOrder("NL: NPO 1 HD") < ChannelGuide.ziggoOrder("RTL 4 FHD"));
        assertTrue(ChannelGuide.ziggoOrder("RTL 4") < ChannelGuide.ziggoOrder("NL SBS 6"));
    }
    @Test public void ultraHighDefinitionGoesAfterRegularStreams() {
        assertEquals(0,ChannelGuide.videoTier("NPO 1 HD"));
        assertEquals(1,ChannelGuide.videoTier("NPO 1 UHD 4K"));
        assertEquals(2,ChannelGuide.videoTier("Test 8K"));
        assertEquals(3,ChannelGuide.videoTier("Test 16K"));
    }
    @Test public void netherlandsCountryRequiresExplicitNlChannelPrefix() {
        assertTrue(ChannelGuide.hasNlPrefix("NL: NPO 1 HD"));
        assertTrue(ChannelGuide.hasNlPrefix(" NL | Ziggo Sport 2 HD"));
        assertTrue(ChannelGuide.hasNlPrefix("nl Cartoon Network"));
        assertTrue(ChannelGuide.hasNlPrefix("NL-NPO 2"));
        assertFalse(ChannelGuide.hasNlPrefix("NPO 1 HD"));
        assertFalse(ChannelGuide.hasNlPrefix("DE | Cartoon Network NL"));
        assertFalse(ChannelGuide.hasNlPrefix("NLSPORT"));
        assertFalse(ChannelGuide.hasNlPrefix("Belgium NL: RTL"));
        assertFalse(ChannelGuide.hasNlPrefix(null));
    }

    @Test public void countryLabelsAndNoProviderLabels() {
        assertEquals("Nederland",ChannelGuide.country("NPO 1 HD",""));
        assertEquals("Duitsland",ChannelGuide.country("DE: ARD HD","DE | Nachrichten"));
    }
    @Test public void top2026MultilanguageMovieCategoryHasPriority() {
        assertTrue(ChannelGuide.isTop2026MultiLang("MULTI LANG | TOP 2026"));
        assertTrue(ChannelGuide.isTop2026MultiLang("Multi Langl: Top 2026"));
        assertFalse(ChannelGuide.isTop2026MultiLang("MULTI LANG | TOP 2025"));
        assertFalse(ChannelGuide.isTop2026MultiLang("TOP 2026 NL"));
    }

    @Test public void providerNamesForTheSameChannelMerge() {
        assertEquals(ChannelGuide.channelIdentity("NL Cartoon Network HD"),
                     ChannelGuide.channelIdentity("Cartoon Network NL FHD"));
        assertEquals(ChannelGuide.channelIdentity("NL Ziggo Sport 2 HD"),
                     ChannelGuide.channelIdentity("Ziggo Sport 2 FHD"));
        assertFalse(ChannelGuide.channelIdentity("Ziggo Sport 2")
                     .equals(ChannelGuide.channelIdentity("Ziggo Sport 3")));
    }

    @Test public void explicitAdultLabelsAreSeparate() {
        assertTrue(ChannelGuide.isAdult("NL XXX","Film"));
        assertTrue(ChannelGuide.isAdult("Adult Movies","Film"));
        assertFalse(ChannelGuide.isAdult("NL Entertainment","NPO 1"));
    }

    @Test public void espnFhdWinsAndAllOtherEspnChannelsSortLast(){
        assertTrue(ChannelGuide.isEspnFhd("NL | ESPN FHD"));
        assertTrue(ChannelGuide.isEspnFhd("US ESPN 2 FHD"));
        assertFalse(ChannelGuide.isEspnFhd("NL ESPN HD"));
        java.util.List<String> list=new java.util.ArrayList<>(java.util.Arrays.asList(
            "NL - ESPN 2 HD","US ESPN FHD","NL - RTL 4",
            "NL - ESPN FHD","NL - NPO 1","ESPN 3 HD"));
        list.sort((a,b)->ChannelGuide.compareLive(a,b,"Alle landen"));
        assertEquals("NL - NPO 1",list.get(0));
        assertEquals("US ESPN FHD",list.get(3));
        assertEquals("NL - ESPN 2 HD",list.get(4));
        assertEquals("ESPN 3 HD",list.get(5));
    }
    @Test public void dutchGuidePlacesEspnHdLast(){
        java.util.List<String> names=new java.util.ArrayList<>(java.util.Arrays.asList(
            "NL - ESPN HD","NL - NPO 1","NL - ESPN FHD","NL - RTL 4"));
        names.sort((a,b)->ChannelGuide.compareLive(a,b,"Nederland"));
        assertEquals("NL - NPO 1",names.get(0));
        assertEquals("NL - ESPN HD",names.get(names.size()-1));
    }
}
