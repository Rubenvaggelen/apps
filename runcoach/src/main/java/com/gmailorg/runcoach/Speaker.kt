package com.gmailorg.runcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Nederlandse spraak. Vraagt tijdelijk audiofocus met "mag zachter" (ducking),
 * zodat Spotify/YouTube Music zachter gaat en daarna vanzelf weer normaal speelt.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()

    private var ready = false
    private var released = false
    private val pending = mutableListOf<String>()
    private var active = 0
    private var counter = 0
    private var persona = Coach.VOICE_NORMAL

    private val abandon = Runnable {
        if (active == 0) audio.abandonAudioFocusRequest(focusRequest)
    }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (released || status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale.forLanguageTag("nl-NL"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.forLanguageTag("nl"))
        }
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finished() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finished() }
            override fun onError(utteranceId: String?, errorCode: Int) { finished() }
        })
        ready = true
        applyVoice()
        val queued = pending.toList()
        pending.clear()
        queued.forEach { say(it) }
    }

    /** Kiest de stem (Coach.VOICE_*): eigen toonhoogte en spreeksnelheid, en waar mogelijk een andere Nederlandse stem. */
    fun setVoice(persona: Int) {
        this.persona = persona
        if (ready && !released) applyVoice()
    }

    private fun applyVoice() {
        try {
            val voices = tts.voices.orEmpty()
                .filter { it.locale.language == "nl" && !it.isNetworkConnectionRequired }
                .sortedBy { it.name }
            if (voices.size > 1) tts.voice = voices[persona.coerceIn(0, voices.size - 1)]
        } catch (e: Exception) {
            // toestel geeft geen stemmenlijst: alleen toonhoogte en snelheid aanpassen
        }
        when (persona) {
            Coach.VOICE_CALM -> { tts.setPitch(1.08f); tts.setSpeechRate(0.94f) }
            Coach.VOICE_STRICT -> { tts.setPitch(0.78f); tts.setSpeechRate(1.14f) }
            else -> { tts.setPitch(1.0f); tts.setSpeechRate(1.0f) }
        }
    }

    /** Altijd aanroepen vanaf de main thread. */
    fun say(text: String) {
        if (released) return
        if (!ready) {
            pending += text
            return
        }
        main.removeCallbacks(abandon)
        if (active == 0) audio.requestAudioFocus(focusRequest)
        active++
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u${counter++}")
        if (result != TextToSpeech.SUCCESS) finished()
    }

    private fun finished() {
        main.post {
            active = (active - 1).coerceAtLeast(0)
            if (active == 0) {
                main.removeCallbacks(abandon)
                main.postDelayed(abandon, 400)
            }
        }
    }

    fun shutdown() {
        released = true
        main.removeCallbacks(abandon)
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {
            // negeren
        }
        audio.abandonAudioFocusRequest(focusRequest)
    }
}
