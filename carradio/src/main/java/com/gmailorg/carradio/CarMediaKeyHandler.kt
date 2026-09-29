package com.gmailorg.carradio

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Fallback voor head-units/CAN-boxen die stuurknoppen als gewone key-events
 * aan de voorgrond-app doorgeven in plaats van via Android MediaSession.
 */
object CarMediaKeyHandler {
    fun handle(context: Context, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return false
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                true
            }

            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
                true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                UsbPlaybackService.toggle(context)
                true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                val state = UsbPlaybackService.snapshot()
                if (!state.isPlaying) UsbPlaybackService.toggle(context)
                true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                val state = UsbPlaybackService.snapshot()
                if (state.isPlaying) UsbPlaybackService.toggle(context)
                true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                UsbPlaybackService.next(context)
                true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                UsbPlaybackService.previous(context)
                true
            }

            KeyEvent.KEYCODE_MEDIA_STOP -> {
                UsbPlaybackService.stop(context)
                true
            }

            else -> false
        }
    }
}
