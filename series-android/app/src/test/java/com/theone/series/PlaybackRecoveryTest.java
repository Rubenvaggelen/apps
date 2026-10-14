package com.theone.series;import org.junit.Test;import static org.junit.Assert.*;
public class PlaybackRecoveryTest {
 @Test public void shortBufferDoesNotAbort(){PlaybackRecovery r=new PlaybackRecovery();assertFalse(r.stalled(1426000,100));assertFalse(r.stalled(1426000,12100));assertTrue(r.stalled(1426000,45100));}
 @Test public void progressAndBackwardSeekRestartDeadline(){PlaybackRecovery r=new PlaybackRecovery();r.progress(20000,0);assertFalse(r.stalled(22000,44000));assertFalse(r.stalled(22000,46000));assertFalse(r.stalled(10000,88000));assertTrue(r.stalled(10000,133000));}
 @Test public void retryBudgetIsBoundedAndStablePlaybackRestoresIt(){PlaybackRecovery r=new PlaybackRecovery();assertEquals(1500,r.retryDelay());assertEquals(3000,r.retryDelay());assertEquals(6000,r.retryDelay());assertEquals(-1,r.retryDelay());r.stable();assertEquals(1500,r.retryDelay());}
}
