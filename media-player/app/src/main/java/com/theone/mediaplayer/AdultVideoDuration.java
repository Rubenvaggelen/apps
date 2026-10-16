package com.theone.mediaplayer;

/** Pure parser for provider-supplied duration metadata; unknown/short clips are rejected. */
public final class AdultVideoDuration {
    private AdultVideoDuration() {}

    public static int parseSeconds(String value) {
        if (value == null || value.trim().isEmpty()) return -1;
        String[] parts = value.trim().split(":");
        if (parts.length != 2 && parts.length != 3) return -1;
        long seconds=0;
        for (String p : parts) {
            if (!p.matches("[0-9]{1,3}")) return -1;
            long n=Long.parseLong(p);
            if (n > 59 && !p.equals(parts[0])) return -1;
            seconds=seconds*60+n;
        }
        return seconds > Integer.MAX_VALUE ? -1 : (int)seconds;
    }
    public static boolean allow(String providerDuration) {
        return parseSeconds(providerDuration) >= 300;
    }
}
