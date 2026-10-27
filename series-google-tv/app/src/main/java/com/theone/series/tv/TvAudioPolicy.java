package com.theone.series.tv;

import java.util.Locale;

/** TV-only audio output settings. Do not affect Series catalog, account, or source order. */
final class TvAudioPolicy {
    private TvAudioPolicy() {}

    static boolean isPhilips(String manufacturer, String brand, String model) {
        String m = manufacturer == null ? "" : manufacturer.toLowerCase(Locale.ROOT);
        String b = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
        String p = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return m.contains("philips") || b.contains("philips") ||
            m.contains("tpvision") || b.contains("tpvision") ||
            p.contains("philips");
    }

    static boolean usePcm(Boolean saved, String manufacturer, String brand, String model) {
        // Explicit user preference always wins. New Philips installs avoid broken
        // Dolby passthrough (reported supported but silent) by preferring PCM.
        return saved != null ? saved : isPhilips(manufacturer, brand, model);
    }
}
