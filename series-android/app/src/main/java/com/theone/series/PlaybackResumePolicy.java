package com.theone.series;

/** Pure policy for resuming an interrupted episode without overriding a manual pause. */
final class PlaybackResumePolicy {
    private PlaybackResumePolicy() { }

    static boolean shouldResume(boolean playWhenReady, boolean userPaused, boolean playbackFailed) {
        return playWhenReady && !userPaused && !playbackFailed;
    }

    static long rememberedPosition(long currentPosition, long previousPosition, boolean playerReady) {
        if (currentPosition > 0 || playerReady) {
            return Math.max(0, currentPosition);
        }
        return Math.max(0, previousPosition);
    }
}
