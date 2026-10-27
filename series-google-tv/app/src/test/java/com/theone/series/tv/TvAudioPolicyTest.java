package com.theone.series.tv;

import org.junit.Test;
import static org.junit.Assert.*;

/** Regressions for reports that Philips calls EAC3 supported but produces silence. */
public final class TvAudioPolicyTest {
    @Test public void philipsDefaultsToPcmInsteadOfDolbyPassthrough() {
        assertTrue(TvAudioPolicy.usePcm(null,"Philips","Philips","55PUS"));
        assertTrue(TvAudioPolicy.usePcm(null,"TPVision","Philips","Google TV"));
        assertTrue(TvAudioPolicy.usePcm(null,"Other","Other","Philips Smart TV"));
    }
    @Test public void unmodifiedSonyTvKeepsWorkingNativeAudio() {
        assertFalse(TvAudioPolicy.usePcm(null,"Sony","BRAVIA","KD-55"));
    }
    @Test public void explicitChoiceAlwaysWins() {
        assertTrue(TvAudioPolicy.usePcm(true,"Sony","Sony","Bravia"));
        assertFalse(TvAudioPolicy.usePcm(false,"Philips","Philips","Philips Google TV"));
    }
}
