package com.theone.series.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackSessionGuardTest {
    @Test public void noticesSixtyMinutePlaybackOrConnectionBoundary() {
        assertTrue(PlaybackSessionGuard.suspectedHourlyCutoff(3600000, 1000));
        assertTrue(PlaybackSessionGuard.suspectedHourlyCutoff(7200000, 3600000));
        assertFalse(PlaybackSessionGuard.suspectedHourlyCutoff(1800000, 1800000));
        assertFalse(PlaybackSessionGuard.suspectedHourlyCutoff(7200000, 7200000));
    }
    @Test public void keepsPositionWhenPlayerTemporarilyReportsZero() {
        assertEquals(3590000, PlaybackSessionGuard.safeProgress(0, 3590000, true));
        assertEquals(90000, PlaybackSessionGuard.safeProgress(90000, 3590000, false));
        assertEquals(0, PlaybackSessionGuard.retryPosition(2000));
        assertEquals(3595000, PlaybackSessionGuard.retryPosition(3600000));
    }
}
