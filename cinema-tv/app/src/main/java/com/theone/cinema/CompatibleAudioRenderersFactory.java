package com.theone.cinema;

import android.content.Context;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/**
 * Some TV HDMI paths report Dolby passthrough support but stay silent when encoded
 * packets reach a PCM-only display. Use the system decoder and send PCM to Android.
 * Unsupported encoded tracks remain unsupported; no fake audio capabilities.
 */
final class CompatibleAudioRenderersFactory extends DefaultRenderersFactory {
    CompatibleAudioRenderersFactory(Context context) {
        super(context);
        setEnableDecoderFallback(true);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected AudioSink buildAudioSink(Context context,
        boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
        return new DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .build();
    }
}
