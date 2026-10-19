package com.theone.mediaplayer;

import org.junit.Test;
import static org.junit.Assert.*;

/** Guard original PCM preference semantics during optional E-AC3 add-on. */
public class Eac3CompatibilityRegressionTest {
    @Test public void standardAudioRemainsDefaultOnGoogleTv() {
        assertFalse(SonyBraviaAudioPolicy.usePcm(null, "Google", "Google", "Chromecast"));
    }
    @Test public void existingPcmOverrideRemainsAvailable() {
        assertTrue(SonyBraviaAudioPolicy.usePcm(true, "Google", "Google", "Chromecast"));
        assertFalse(SonyBraviaAudioPolicy.usePcm(false, "Google", "Google", "Chromecast"));
    }
}
