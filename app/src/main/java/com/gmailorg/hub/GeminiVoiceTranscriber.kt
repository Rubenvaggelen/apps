package com.gmailorg.hub

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Volledige transcriptie van de radio-opname. Korte audio gaat in één request;
 * langere audio wordt in overlappende stukken parallel verwerkt zodat de laatste
 * helft van een lang bericht niet wegvalt en de totale wachttijd beperkt blijft.
 */
object GeminiVoiceTranscriber {
    private const val TAG = "CarVoiceTranscriber"
    private const val MODEL = "gemini-3.6-flash"

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    private data class WavInfo(val sampleRate: Int, val pcm: ByteArray) {
        val durationMs: Long
            get() = if (sampleRate <= 0) 0L else (pcm.size.toLong() * 1000L) / (sampleRate * 2L)
    }

    fun durationMs(wavBytes: ByteArray): Long = parseWav(wavBytes)?.durationMs ?: 0L

    fun transcribe(wavBytes: ByteArray, callback: (Result) -> Unit) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            callback(Result.Error("Gemini API-key ontbreekt op de telefoon"))
            return
        }
        val info = parseWav(wavBytes)
        if (info == null || info.pcm.isEmpty()) {
            callback(Result.Error("lege of ongeldige audio-opname"))
            return
        }

        Thread({
            try {
                val text = if (info.durationMs <= 14_000L) {
                    requestTranscription(wavBytes, apiKey, 1, 1)
                } else {
                    transcribeChunks(info, apiKey)
                }
                if (text.isBlank()) callback(Result.Error("geen spraak herkend"))
                else callback(Result.Success(text))
            } catch (e: Exception) {
                Log.e(TAG, "Transcriptie mislukt", e)
                callback(Result.Error(e.message ?: e.javaClass.simpleName))
            }
        }, "TheOne-GeminiVoice").start()
    }

    private fun transcribeChunks(info: WavInfo, apiKey: String): String {
        val bytesPerSecond = info.sampleRate * 2
        val chunkSamplesBytes = bytesPerSecond * 12
        val overlapBytes = (bytesPerSecond * 0.35).toInt()
        val step = (chunkSamplesBytes - overlapBytes).coerceAtLeast(bytesPerSecond * 8)
        val chunks = mutableListOf<ByteArray>()
        var start = 0
        while (start < info.pcm.size) {
            val end = minOf(info.pcm.size, start + chunkSamplesBytes)
            chunks += makeWav(info.pcm.copyOfRange(start, end), info.sampleRate)
            if (end >= info.pcm.size) break
            start += step
        }

        val pool = Executors.newFixedThreadPool(minOf(4, chunks.size.coerceAtLeast(1)))
        return try {
            val futures = chunks.mapIndexed { index, bytes ->
                pool.submit(Callable {
                    runCatching { requestTranscription(bytes, apiKey, index + 1, chunks.size) }.getOrDefault("")
                })
            }
            val parts = futures.map { future ->
                runCatching { future.get(18, TimeUnit.SECONDS) }.getOrDefault("").trim()
            }
            val good = parts.filter { it.isNotBlank() }
            if (good.isEmpty()) throw Exception("geen transcriptie ontvangen")
            mergeSegments(good)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun mergeSegments(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        var result = parts.first().trim()
        for (i in 1 until parts.size) {
            val next = parts[i].trim()
            if (next.isBlank()) continue
            val left = result.split(Regex("\\s+")).filter { it.isNotBlank() }
            val right = next.split(Regex("\\s+")).filter { it.isNotBlank() }
            var overlap = 0
            val maxOverlap = minOf(8, left.size, right.size)
            for (n in maxOverlap downTo 1) {
                val a = left.takeLast(n).joinToString(" ").lowercase()
                val b = right.take(n).joinToString(" ").lowercase()
                if (a == b) { overlap = n; break }
            }
            result = (result.trimEnd() + " " + right.drop(overlap).joinToString(" ")).trim()
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun requestTranscription(wavBytes: ByteArray, apiKey: String, part: Int, total: Int): String {
        val audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val prompt = if (total <= 1) {
            "Transcribeer de VOLLEDIGE Nederlandse spraakopname exact van begin tot einde. " +
                "Sla geen woorden of zinnen na een pauze over. Geef alleen de gesproken tekst terug."
        } else {
            "Dit is deel $part van $total van één langer Nederlands WhatsApp-antwoord. " +
                "Transcribeer ALLE hoorbare woorden in dit deel exact, ook woorden na een korte pauze. " +
                "Geef alleen de gesproken tekst terug, zonder uitleg of opmaak."
        }
        val parts = JSONArray().apply {
            put(JSONObject().apply { put("text", prompt) })
            put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "audio/wav")
                    put("data", audio)
                })
            })
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply { put("parts", parts) }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("maxOutputTokens", 512)
            })
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 4_000
        connection.readTimeout = 14_000
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(response)
        if (code !in 200..299) {
            val msg = json.optJSONObject("error")?.optString("message") ?: "HTTP $code"
            throw Exception(msg)
        }
        val candidates = json.optJSONArray("candidates") ?: throw Exception("geen transcriptie ontvangen")
        if (candidates.length() == 0) throw Exception("geen transcriptie ontvangen")
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: throw Exception("lege transcriptie")
        val responseParts = content.optJSONArray("parts") ?: throw Exception("lege transcriptie")
        if (responseParts.length() == 0) throw Exception("lege transcriptie")
        return responseParts.getJSONObject(0).optString("text").trim()
    }

    private fun parseWav(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val channels = header.getShort(22).toInt()
        val sampleRate = header.getInt(24)
        val bits = header.getShort(34).toInt()
        if (channels != 1 || bits != 16 || sampleRate !in 8_000..48_000) return null
        return WavInfo(sampleRate, bytes.copyOfRange(44, bytes.size))
    }

    private fun makeWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
