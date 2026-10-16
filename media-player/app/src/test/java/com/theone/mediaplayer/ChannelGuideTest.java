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
    @Test public void countryLabelsAndNoProviderLabels() {
        assertEquals("Nederland",ChannelGuide.country("NPO 1 HD",""));
        assertEquals("Duitsland",ChannelGuide.country("DE: ARD HD","DE | Nachrichten"));
    }
    @Test public void explicitAdultLabelsAreSeparate() {
        assertTrue(ChannelGuide.isAdult("NL XXX","Film"));
        assertTrue(ChannelGuide.isAdult("Adult Movies","Film"));
        assertFalse(ChannelGuide.isAdult("NL Entertainment","NPO 1"));
    }
}
