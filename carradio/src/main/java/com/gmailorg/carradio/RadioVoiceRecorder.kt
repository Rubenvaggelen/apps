package com.gmailorg.carradio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Neemt rechtstreeks PCM op van de K2401-microfoon.
 * De stilte-detectie kalibreert zichzelf op het achtergrondgeluid in de auto,
 * zodat langere antwoorden en natuurlijke pauzes niet voortijdig worden afgekapt.
 */
class RadioVoiceRecorder {
    sealed class Result {
        data class Success(val wavBytes: ByteArray, val durationMs: Long) : Result()
        data class Error(val message: String) : Result()
    }

    private val recording = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null

    fun isRecording(): Boolean = recording.get()

    fun start(onLevel: ((Double) -> Unit)? = null, onComplete: (Result) -> Unit) {
        if (!recording.compareAndSet(false, true)) {
            onComplete(Result.Error("opname is al bezig"))
            return
        }

        Thread({
            var recorder: AudioRecord? = null
            try {
                val config = createRecorder() ?: throw IllegalStateException("microfoon kon niet worden geopend")
                recorder = config.first
                val sampleRate = config.second
                audioRecord = recorder

                val pcm = ByteArrayOutputStream()
                val buffer = ShortArray((sampleRate / 25).coerceAtLeast(320)) // ~40 ms
                val started = System.currentTimeMillis()
                var heardSpeech = false
                var silentSince = 0L
                var lastSpeechBytePos = 0

                // Eerste ~350 ms wordt gebruikt om motorgeluid/ruis te meten.
                var noiseSamples = 0
                var noiseTotal = 0.0
                var noiseFloor = 220.0

                recorder.startRecording()
                while (recording.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = buffer[i].toDouble()
                        sumSq += s * s
                        pcm.write(buffer[i].toInt() and 0xff)
                        pcm.write((buffer[i].toInt() shr 8) and 0xff)
                    }
                    val rms = sqrt(sumSq / read.coerceAtLeast(1))
                    onLevel?.invoke(rms)

                    val now = System.currentTimeMillis()
                    val elapsed = now - started
                    if (elapsed < 350L) {
                        noiseTotal += rms
                        noiseSamples++
                        if (noiseSamples > 0) noiseFloor = noiseTotal / noiseSamples
                    }

                    val speechThreshold = max(650.0, noiseFloor * 2.15)
                    val silenceThreshold = max(360.0, noiseFloor * 1.35)

                    if (rms >= speechThreshold) {
                        heardSpeech = true
                        silentSince = 0L
                        lastSpeechBytePos = pcm.size()
                    } else if (heardSpeech) {
                        if (rms <= silenceThreshold) {
                            if (silentSince == 0L) silentSince = now
                        } else {
                            // Zachte syllabe: nog niet stoppen.
                            silentSince = 0L
                            lastSpeechBytePos = pcm.size()
                        }
                    }

                    // Geef ruimte voor langere zinnen en natuurlijke pauzes.
                    // Pas na 10 seconden echte stilte wordt het antwoord automatisch verstuurd.
                    if (heardSpeech && silentSince > 0L && now - silentSince >= 10_000L && elapsed >= 900L) break
                    // Niet eindeloos wachten als iemand helemaal niet begint te praten.
                    if (!heardSpeech && elapsed >= 15_000L) break
                    // Lange WhatsApp-antwoorden mogen maximaal één minuut duren.
                    if (elapsed >= 60_000L) break
                }

                val duration = System.currentTimeMillis() - started
                val raw = pcm.toByteArray()
                if (!heardSpeech || raw.size < sampleRate / 3) {
                    onComplete(Result.Error("geen duidelijke spraak opgenomen"))
                } else {
                    // Gooi overtollige trailing silence weg; maximaal ~250 ms bewaren.
                    val tailBytes = (sampleRate * 2 * 0.25).toInt()
                    val usefulSize = (lastSpeechBytePos + tailBytes).coerceIn(sampleRate / 4, raw.size)
                    onComplete(Result.Success(makeWav(raw.copyOf(usefulSize), sampleRate), duration))
                }
            } catch (e: SecurityException) {
                onComplete(Result.Error("geen microfoontoestemming"))
            } catch (e: Exception) {
                onComplete(Result.Error(e.message ?: e.javaClass.simpleName))
            } finally {
                recording.set(false)
                audioRecord = null
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
            }
        }, "TheOne-RadioMic").start()
    }

    fun stop() {
        recording.set(false)
    }

    private fun createRecorder(): Pair<AudioRecord, Int>? {
        // Probeer 8 kHz eerst: ruim voldoende voor WhatsApp-spraak en halveert de overdrachtstijd.
        val sampleRates = intArrayOf(8_000, 16_000, 44_100)
        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

        for (sampleRate in sampleRates) {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            for (source in sources) {
                try {
                    val recorder = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes((min * 2).coerceAtLeast(4096))
                        .build()
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder to sampleRate
                    recorder.release()
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun makeWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
