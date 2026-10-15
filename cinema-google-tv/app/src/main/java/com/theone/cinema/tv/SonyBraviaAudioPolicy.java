package com.theone.cinema.tv;

import java.util.Locale;

/**
 * Audio output profile for Sony Bravia Android/Google TV.
 * The working Series app uses Android's native automatic track selection.
 * On Bravia, prefer software-decoded PCM instead of device-reported Dolby
 * passthrough by default. Any previously selected manual setting wins.
 */
final class SonyBraviaAudioPolicy {
    private SonyBraviaAudioPolicy() {}

    static boolean autoPcmFor(String manufacturer, String brand, String model) {
        String m = manufacturer == null ? "" : manufacturer.toLowerCase(Locale.ROOT);
        String b = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
        String p = model == null ? "" : model.toLowerCase(Locale.ROOT);
        // These are TV-only APKs. Sony reports different model identifiers
        // across Bravia generations, so do not depend on a single model prefix.
        return (m.contains("sony") || b.contains("sony")) && !p.contains("xperia");
    }

    static boolean usePcm(Boolean explicitSetting, String manufacturer, String brand, String model) {
        return explicitSetting != null ? explicitSetting :
            autoPcmFor(manufacturer, brand, model);
    }
}
