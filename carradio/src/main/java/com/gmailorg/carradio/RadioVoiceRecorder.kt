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
 * De opname stopt pas na 5 seconden echte stilte of na 90 seconden totaal.
 * De volledige gesproken opname blijft behouden; alleen een deel van de
 * trailing stilte wordt verwijderd om overdracht/transcriptie sneller te maken.
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
                var stoppedForSilence = false

                // Kalibreer de eerste halve seconde op motor-/cabinegeluid.
                var noiseSamples = 0
                var noiseTotal = 0.0
                var noiseFloor = 220.0

                recorder.startRecording()
                while (recording.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i].toDouble()
                        sumSq += sample * sample
                        pcm.write(buffer[i].toInt() and 0xff)
                        pcm.write((buffer[i].toInt() shr 8) and 0xff)
                    }
                    val rms = sqrt(sumSq / read.coerceAtLeast(1))
                    onLevel?.invoke(rms)

                    val now = System.currentTimeMillis()
                    val elapsed = now - started
                    if (elapsed < 500L) {
                        noiseTotal += rms
                        noiseSamples++
                        if (noiseSamples > 0) noiseFloor = noiseTotal / noiseSamples
                    }

                    // Start iets strenger, maar zodra spraak gehoord is houden we
                    // zachte woorden/syllabes veel langer als actieve spraak vast.
                    val startThreshold = max(420.0, max(noiseFloor * 1.55, noiseFloor + 220.0))
                    val continueThreshold = max(260.0, max(noiseFloor * 1.22, noiseFloor + 90.0))

                    if (!heardSpeech) {
                        if (elapsed >= 400L && rms >= startThreshold) {
                            heardSpeech = true
                            silentSince = 0L
                        }
                    } else {
                        if (rms >= continueThreshold) {
                            silentSince = 0L
                        } else if (silentSince == 0L) {
                            silentSince = now
                        }
                    }

                    if (heardSpeech && silentSince > 0L && now - silentSince >= 5_000L && elapsed >= 1_000L) {
                        stoppedForSilence = true
                        break
                    }
                    if (!heardSpeech && elapsed >= 18_000L) break
                    if (elapsed >= 90_000L) break
                }

                val duration = System.currentTimeMillis() - started
                val raw = pcm.toByteArray()
                if (!heardSpeech || raw.size < sampleRate / 3) {
                    onComplete(Result.Error("geen duidelijke spraak opgenomen"))
                } else {
                    // Als de automatische 5-sec stilte-stop de opname beëindigde,
                    // laat ~750 ms stilte staan. We knippen dus nooit midden in
                    // een zin op basis van een oude 'laatste spraak'-positie.
                    val useful = if (stoppedForSilence) {
                        val removeBytes = (sampleRate * 2 * 4.25).toInt()
                        (raw.size - removeBytes).coerceAtLeast((sampleRate * 2 * 0.8).toInt())
                    } else raw.size
                    onComplete(Result.Success(makeWav(raw.copyOf(useful), sampleRate), duration))
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
