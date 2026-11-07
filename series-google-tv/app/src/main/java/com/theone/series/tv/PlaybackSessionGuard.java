package com.theone.series.tv;

final class PlaybackSessionGuard {
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final long WINDOW_MS = 2L * 60L * 1000L;
    private PlaybackSessionGuard() {}

    static boolean suspectedHourlyCutoff(long positionMs, long connectionAgeMs) {
        return nearHour(positionMs) || nearHour(connectionAgeMs);
    }
    private static boolean nearHour(long valueMs) {
        return valueMs >= HOUR_MS - WINDOW_MS && valueMs <= HOUR_MS + WINDOW_MS;
    }
    /** A full TV episode cannot legitimately finish after a short connection:
     * treat early transport EOF as a failed source, not as an episode finale. */
    static boolean suspectedPrematureEnd(long positionMs,long connectionAgeMs,long durationMs) {
        if(connectionAgeMs<0||connectionAgeMs>3L*60L*1000L||positionMs>3L*60L*1000L)return false;
        // Explicitly short specials and previews may really be finished.
        return durationMs<=0||durationMs>=12L*60L*1000L;
    }
    static long retryPosition(long positionMs) {
        return Math.max(0L, positionMs - 5000L);
    }
    static long safeProgress(long reportedMs, long savedMs, boolean preparingOrEnded) {
        long position = Math.max(0L, reportedMs);
        return preparingOrEnded && position < 10000L
            ? Math.max(position, Math.max(0L, savedMs))
            : position;
    }
}
