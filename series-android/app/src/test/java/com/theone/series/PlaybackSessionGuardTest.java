package com.theone.series;
import org.junit.Test;import static org.junit.Assert.*;
public class PlaybackSessionGuardTest {
 @Test public void hourlyStreamCutoffDetected(){assertTrue(PlaybackSessionGuard.suspectedHourlyCutoff(3600000L,0L));assertTrue(PlaybackSessionGuard.suspectedHourlyCutoff(7200000L,3600000L));assertFalse(PlaybackSessionGuard.suspectedHourlyCutoff(1800000L,0L));}
 @Test public void retainsPositionUntilPlaybackResumes(){assertEquals(3590000L,PlaybackSessionGuard.safeProgress(0,3590000L,true));assertEquals(20000L,PlaybackSessionGuard.safeProgress(20000,3590000,false));assertEquals(3595000L,PlaybackSessionGuard.retryPosition(3600000L));}
}