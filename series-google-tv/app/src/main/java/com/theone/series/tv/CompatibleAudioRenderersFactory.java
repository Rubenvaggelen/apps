package com.theone.series.tv;

import android.content.Context;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/**
 * PCM compatibility mode adapted from the working The One Media Player TV renderer.
 * Prevents Dolby passthrough when a Google TV/display accepts only decoded PCM.
 * This renderer is TV-only and enabled by the user-selectable audio setting.
 */
final class CompatibleAudioRenderersFactory extends DefaultRenderersFactory {
    CompatibleAudioRenderersFactory(Context context) {
        super(context);
        setEnableDecoderFallback(true);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected AudioSink buildAudioSink(Context context,
                                       boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        return new DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .build();
    }
}
