package com.theone.mediaplayer;

import org.junit.Test;
import static org.junit.Assert.*;

public class SonyBraviaAudioPolicyTest {
    @Test public void braviaUsesOriginalRendererByDefault() {
        assertFalse(SonyBraviaAudioPolicy.usePcm(null, "Sony", "Sony", "BRAVIA 4K GB"));
        assertFalse(SonyBraviaAudioPolicy.usePcm(null, "Sony", "Sony", "KD-55XH9005"));
        assertFalse(SonyBraviaAudioPolicy.usePcm(null, "Sony Corporation", "Sony", "XR-A80L"));
    }
    @Test public void otherAndroidTvDevicesUseOriginalRendererByDefault() {
        assertFalse(SonyBraviaAudioPolicy.usePcm(null, "Google", "Google", "Chromecast"));
        assertFalse(SonyBraviaAudioPolicy.usePcm(null, "Samsung", "Samsung", "Android TV"));
        assertFalse(SonyBraviaAudioPolicy.usePcm(null, "Xiaomi", "Xiaomi", "Mi Box"));
    }
    @Test public void manualOverrideRemainsAuthoritative() {
        assertFalse(SonyBraviaAudioPolicy.usePcm(false, "Sony", "Sony", "BRAVIA"));
        assertTrue(SonyBraviaAudioPolicy.usePcm(true, "Sony", "Sony", "BRAVIA"));
        assertTrue(SonyBraviaAudioPolicy.usePcm(true, "Google", "Google", "Chromecast"));
    }
}
