package com.theone.mediaplayer;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdultVideoDurationTest {
    @Test public void neverShowClipsUnderFiveMinutes() {
        assertFalse(AdultVideoDuration.allow("0:01"));
        assertFalse(AdultVideoDuration.allow("04:59"));
        assertFalse(AdultVideoDuration.allow("4:59"));
        assertFalse(AdultVideoDuration.allow(""));
        assertFalse(AdultVideoDuration.allow(null));
        assertFalse(AdultVideoDuration.allow("unknown"));
        assertFalse(AdultVideoDuration.allow("1:60"));
        assertTrue(AdultVideoDuration.allow("05:00"));
        assertTrue(AdultVideoDuration.allow("16:40"));
        assertTrue(AdultVideoDuration.allow("1:05:00"));
    }
}
