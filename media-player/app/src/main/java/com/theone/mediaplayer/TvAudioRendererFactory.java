package com.theone.mediaplayer;

import android.content.Context;
import android.util.Log;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import java.lang.reflect.Method;

/**
 * TV-only optional software audio extension. Media Player on Android phones
 * keeps its original renderer and dependency graph unchanged.
 */
final class TvAudioRendererFactory {
    private TvAudioRendererFactory() {}

    static DefaultRenderersFactory create(Context context) {
        try {
            // TvEac3Support exists only in the tvapp source set.
            Class<?> extension = Class.forName("com.theone.mediaplayer.TvEac3Support");
            Method create = extension.getMethod("create", Context.class);
            Object factory = create.invoke(null, context);
            if (factory instanceof DefaultRenderersFactory) {
                return (DefaultRenderersFactory) factory;
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            // The original Android renderer remains available without the add-on.
            Log.w("TheOneMediaPlayer", "Optional TV audio extension unavailable; preserving native renderer", failure);
        }
        return new DefaultRenderersFactory(context).setEnableDecoderFallback(true);
    }
}
