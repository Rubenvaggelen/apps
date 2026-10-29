package com.theone.series;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackResumePolicyTest {
    @Test public void playingEpisodeResumesAfterSwitchingApps() {
        assertTrue(PlaybackResumePolicy.shouldResume(true, false, false));
    }

    @Test public void manuallyPausedEpisodeStaysPaused() {
        assertFalse(PlaybackResumePolicy.shouldResume(false, true, false));
        assertFalse(PlaybackResumePolicy.shouldResume(true, true, false));
    }

    @Test public void failedPlaybackIsNotAutomaticallyRestarted() {
        assertFalse(PlaybackResumePolicy.shouldResume(true, false, true));
    }

    @Test public void preservesPositionWhenStreamMomentarilyReportsZeroWhileBuffering() {
        assertEquals(121000, PlaybackResumePolicy.rememberedPosition(0, 121000, false));
    }

    @Test public void respectsIntentionalRewindAndSeekToBeginning() {
        assertEquals(20000, PlaybackResumePolicy.rememberedPosition(20000, 121000, true));
        assertEquals(0, PlaybackResumePolicy.rememberedPosition(0, 121000, true));
    }

    @Test public void neverPersistsNegativePositions() {
        assertEquals(0, PlaybackResumePolicy.rememberedPosition(-1, -5, false));
    }
}
