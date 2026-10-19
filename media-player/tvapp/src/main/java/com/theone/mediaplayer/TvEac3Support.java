package com.theone.mediaplayer;

import android.content.Context;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

/**
 * Additional TV decoder. ON keeps system codecs first; FFmpeg is selected only
 * for tracks that the standard MediaCodec renderer cannot play (e.g. E-AC3).
 * No changes to the default Xtream source or to the manual PCM profile.
 */
public final class TvEac3Support {
    private TvEac3Support() {}

    public static DefaultRenderersFactory create(Context context) {
        NextRenderersFactory factory = new NextRenderersFactory(context);
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        factory.setEnableDecoderFallback(true);
        return factory;
    }
}
