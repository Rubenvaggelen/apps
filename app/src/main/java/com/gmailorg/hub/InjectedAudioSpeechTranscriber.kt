package com.gmailorg.hub

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Snelle transcriptieroute op de telefoon (Android 13+).
 * Probeert segmented recognition zodat een pauze midden in een lange zin niet
 * alleen het eerste deel oplevert. Gemini blijft de volledige fallback.
 */
object InjectedAudioSpeechTranscriber {
    sealed class Result {
        data class Success(val text: String, val complete: Boolean) : Result()
        data class Error(val message: String) : Result()
    }

    private data class WavInfo(val sampleRate: Int, val pcm: ByteArray) {
        val durationMs: Long
            get() = if (sampleRate <= 0) 0L else (pcm.size.toLong() * 1000L) / (sampleRate * 2L)
    }

    fun transcribe(context: Context, wavBytes: ByteArray, callback: (Result) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            callback(Result.Error("Android audio-injectie vereist Android 13+"))
            return
        }

        val info = parseWav(wavBytes)
        if (info == null || info.pcm.isEmpty()) {
            callback(Result.Error("ongeldige WAV-opname"))
            return
        }

        val main = Handler(Looper.getMainLooper())
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                callback(Result.Error("spraakherkenning niet beschikbaar op telefoon"))
                return@post
            }

            var recognizer: SpeechRecognizer? = null
            var readFd: ParcelFileDescriptor? = null
            var writeFd: ParcelFileDescriptor? = null
            var timeoutRunnable: Runnable? = null
            val finished = AtomicBoolean(false)
            val segments = mutableListOf<String>()
            var latestPartial = ""

            fun addSegment(bundle: Bundle?) {
                val text = bundle
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotBlank() && (segments.isEmpty() || !segments.last().equals(text, true))) {
                    segments += text
                }
            }

            fun combinedText(): String = segments.joinToString(" ").replace(Regex("\\s+"), " ").trim()

            fun finish(result: Result) {
                if (!finished.compareAndSet(false, true)) return
                timeoutRunnable?.let { main.removeCallbacks(it) }
                try { recognizer?.cancel() } catch (_: Exception) {}
                try { recognizer?.destroy() } catch (_: Exception) {}
                try { readFd?.close() } catch (_: Exception) {}
                try { writeFd?.close() } catch (_: Exception) {}
                callback(result)
            }

            try {
                val pipe = ParcelFileDescriptor.createPipe()
                readFd = pipe[0]
                writeFd = pipe[1]
                recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onPartialResults(partialResults: Bundle?) {
                        latestPartial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                    }

                    override fun onSegmentResults(segmentResults: Bundle) {
                        addSegment(segmentResults)
                    }

                    override fun onEndOfSegmentedSession() {
                        val text = combinedText().ifBlank { latestPartial }
                        if (text.isBlank()) finish(Result.Error("geen tekst herkend"))
                        else finish(Result.Success(text, complete = true))
                    }

                    override fun onError(error: Int) {
                        val text = combinedText().ifBlank { latestPartial }
                        if (text.isNotBlank()) finish(Result.Success(text, complete = false))
                        else finish(Result.Error("spraakfout $error"))
                    }

                    override fun onResults(results: Bundle?) {
                        addSegment(results)
                        val text = combinedText().ifBlank { latestPartial }
                        if (text.isBlank()) finish(Result.Error("geen tekst herkend"))
                        else finish(Result.Success(text, complete = info.durationMs <= 8_000L))
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, info.sampleRate)
                    // Segmented session: laat de recognizer na een pauze verder luisteren
                    // naar de rest van de vooraf opgenomen audio.
                    putExtra("android.speech.extra.SEGMENTED_SESSION", RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 5_000L)
                    putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 5_000L)
                    putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", info.durationMs.coerceAtMost(90_000L))
                }

                recognizer?.startListening(intent)

                val outputFd = writeFd ?: throw IllegalStateException("audio pipe ontbreekt")
                thread(name = "TheOne-InjectedSpeechAudio") {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(outputFd).use { out ->
                            out.write(info.pcm)
                            out.flush()
                        }
                    } catch (_: Exception) {
                        main.post { finish(Result.Error("audio-injectie mislukt")) }
                    }
                }

                val timeoutMs = (info.durationMs + 7_000L).coerceIn(8_000L, 25_000L)
                timeoutRunnable = Runnable {
                    val text = combinedText().ifBlank { latestPartial }
                    if (text.isNotBlank()) finish(Result.Success(text, complete = false))
                    else finish(Result.Error("snelle spraakherkenning timeout"))
                }
                main.postDelayed(timeoutRunnable!!, timeoutMs)
            } catch (e: Exception) {
                finish(Result.Error(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun parseWav(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val channels = header.getShort(22).toInt()
        val sampleRate = header.getInt(24)
        val bitsPerSample = header.getShort(34).toInt()
        if (channels != 1 || bitsPerSample != 16 || sampleRate !in 8_000..48_000) return null
        return WavInfo(sampleRate, bytes.copyOfRange(44, bytes.size))
    }
}
