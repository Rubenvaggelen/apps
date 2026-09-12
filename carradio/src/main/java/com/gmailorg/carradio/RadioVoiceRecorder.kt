package com.gmailorg.carradio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Neemt rechtstreeks PCM op van de microfoon van deze headunit.
 * Geen Google SpeechRecognizer nodig: de WAV gaat via Bluetooth naar de telefoon.
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
                val config = createRecorder()
                    ?: throw IllegalStateException("microfoon kon niet worden geopend")
                recorder = config.first
                val sampleRate = config.second
                audioRecord = recorder

                val pcm = ByteArrayOutputStream()
                val buffer = ShortArray(1024)
                var heardSpeech = false
                var silentSince = 0L
                val started = System.currentTimeMillis()

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
                    if (rms > 750.0) {
                        heardSpeech = true
                        silentSince = 0L
                    } else if (heardSpeech && rms < 420.0) {
                        if (silentSince == 0L) silentSince = now
                    } else if (rms >= 420.0) {
                        silentSince = 0L
                    }

                    val elapsed = now - started
                    if (heardSpeech && silentSince > 0L && now - silentSince > 1400L && elapsed > 1200L) break
                    if (elapsed > 12_000L) break
                }

                val duration = System.currentTimeMillis() - started
                val pcmBytes = pcm.toByteArray()
                if (pcmBytes.size < sampleRate / 2) {
                    onComplete(Result.Error("geen duidelijke spraak opgenomen"))
                } else {
                    onComplete(Result.Success(makeWav(pcmBytes, sampleRate), duration))
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
        val sampleRates = intArrayOf(16_000, 44_100, 8_000)
        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

        for (sampleRate in sampleRates) {
            val min = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
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
                } catch (_: Exception) {
                    // volgende bron/sample rate proberen
                }
            }
        }
        return null
    }

    private fun makeWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = sampleRate * 2 // mono, 16-bit
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(1.toShort()) // mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2.toShort()) // block align
        header.putShort(16.toShort()) // bits/sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
