package com.gmailorg.carradio

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Gewone Android-apparaten gebruiken AudioManager.
 * K2401-ROMs die dat negeren vallen terug op een echt hardware-volume-keyevent.
 */
object CarVolumeControl {
    private const val PREFS = "car_master_volume"
    private const val KEY_LAST = "last_level"
    private const val KEY_HARDWARE_FALLBACK = "hardware_fallback"

    fun max(context: Context): Int =
        audio(context).getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun current(context: Context): Int {
        val am = audio(context)
        val max = max(context)
        val system = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_HARDWARE_FALLBACK, false)) {
            prefs.getInt(KEY_LAST, system).coerceIn(0, max)
        } else {
            rememberLevel(context, system)
            system
        }
    }

    fun raise(context: Context) =
        adjust(context, AudioManager.ADJUST_RAISE, KeyEvent.KEYCODE_VOLUME_UP, +1)

    fun lower(context: Context) =
        adjust(context, AudioManager.ADJUST_LOWER, KeyEvent.KEYCODE_VOLUME_DOWN, -1)

    fun toggleMute(context: Context) {
        val am = audio(context)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        runCatching {
            am.adjustSuggestedStreamVolume(
                AudioManager.ADJUST_TOGGLE_MUTE,
                AudioManager.STREAM_MUSIC,
                AudioManager.FLAG_SHOW_UI
            )
        }
        val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (after == before && fallbackEnabled(context)) {
            Thread { rootKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE) }.start()
        }
    }

    fun set(context: Context, target: Int) {
        val max = max(context)
        val wanted = target.coerceIn(0, max)

        if (fallbackEnabled(context)) {
            val before = rememberedLevel(context).coerceIn(0, max)
            rememberLevel(context, wanted)
            val delta = wanted - before
            if (delta != 0) {
                val keyCode =
                    if (delta > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                Thread {
                    repeat(abs(delta).coerceAtMost(max)) { rootKeyEvent(keyCode) }
                }.start()
            }
            return
        }

        val am = audio(context)
        runCatching {
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                wanted,
                AudioManager.FLAG_SHOW_UI
            )
        }
        val after = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)

        if (after == wanted) {
            setFallback(context, false)
            rememberLevel(context, after)
            return
        }

        setFallback(context, true)
        val delta = wanted - after
        rememberLevel(context, wanted)
        if (delta != 0) {
            val keyCode =
                if (delta > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
            Thread {
                repeat(abs(delta).coerceAtMost(max)) { rootKeyEvent(keyCode) }
            }.start()
        }
    }

    private fun adjust(
        context: Context,
        direction: Int,
        keyCode: Int,
        rememberedDelta: Int
    ) {
        val max = max(context)

        if (fallbackEnabled(context)) {
            val next = (rememberedLevel(context) + rememberedDelta).coerceIn(0, max)
            rememberLevel(context, next)
            Thread { rootKeyEvent(keyCode) }.start()
            return
        }

        val am = audio(context)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        runCatching {
            am.adjustSuggestedStreamVolume(
                direction,
                AudioManager.STREAM_MUSIC,
                AudioManager.FLAG_SHOW_UI
            )
        }
        val after = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)

        if (after != before) {
            setFallback(context, false)
            rememberLevel(context, after)
        } else {
            setFallback(context, true)
            rememberLevel(context, (before + rememberedDelta).coerceIn(0, max))
            Thread { rootKeyEvent(keyCode) }.start()
        }
    }

    private fun fallbackEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HARDWARE_FALLBACK, false)

    private fun setFallback(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HARDWARE_FALLBACK, enabled)
            .apply()
    }

    private fun rememberedLevel(context: Context): Int {
        val am = audio(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST, am.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    private fun rememberLevel(context: Context, level: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST, level.coerceIn(0, max(context)))
            .apply()
    }

    private fun audio(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun rootKeyEvent(keyCode: Int): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "input keyevent $keyCode")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(900, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        }
    }
}
