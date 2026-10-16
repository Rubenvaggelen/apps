package com.theone.mediaplayer;

import java.util.regex.Pattern;

/** Fail-closed length gate for third-party adult embeds. */
public final class PornhubDurationFilter {
    public static final int MINIMUM_SECONDS = 5 * 60;
    private static final Pattern VIDEO_ID = Pattern.compile("[a-zA-Z0-9]{8,40}");

    private PornhubDurationFilter() {}

    /** Accept only MM:SS or HH:MM:SS, never guess when metadata is absent. */
    public static int seconds(String duration) {
        if (duration == null || !duration.matches("[0-9]{1,3}:[0-9]{2}(:[0-9]{2})?")) return -1;
        String[] parts = duration.split(":");
        try {
            int last = Integer.parseInt(parts[parts.length - 1]);
            int min = Integer.parseInt(parts[parts.length - 2]);
            if (last > 59 || min > 59) return -1;
            int hours = parts.length == 3 ? Integer.parseInt(parts[0]) : 0;
            if (hours > 100) return -1;
            return (hours * 3600) + (min * 60) + last;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static boolean permits(String duration) {
        return seconds(duration) >= MINIMUM_SECONDS;
    }

    public static boolean safeVideoId(String videoId) {
        return videoId != null && VIDEO_ID.matcher(videoId).matches();
    }

    /** No bypass through watch URLs, copied embeds, or query parameters. */
    public static String embedUrl(String videoId) {
        if (!safeVideoId(videoId)) return null;
        return "https://www.pornhub.com/embed/" + videoId;
    }
}
