package com.theone.series.tv;

import android.content.Context;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/** Same output path as the working Media Player TV's PCM compatibility mode. */
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
