package com.gmailorg.hub

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zet een korte WAV-opname van de autoradio om naar tekst via dezelfde Gemini-key
 * die The One al gebruikt. De radio hoeft daardoor zelf geen SpeechRecognizer of
 * Google Voice Typing te ondersteunen.
 */
object GeminiVoiceTranscriber {
    private const val TAG = "CarVoiceTranscriber"
    private const val MODEL = "gemini-3.6-flash"

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    fun transcribe(wavBytes: ByteArray, callback: (Result) -> Unit) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            callback(Result.Error("Gemini API-key ontbreekt op de telefoon"))
            return
        }
        if (wavBytes.isEmpty()) {
            callback(Result.Error("lege audio-opname"))
            return
        }

        Thread {
            try {
                val text = requestTranscription(wavBytes, apiKey)
                if (text.isBlank()) callback(Result.Error("geen spraak herkend"))
                else callback(Result.Success(text))
            } catch (e: Exception) {
                Log.e(TAG, "Transcriptie mislukt", e)
                callback(Result.Error(e.message ?: e.javaClass.simpleName))
            }
        }.start()
    }

    private fun requestTranscription(wavBytes: ByteArray, apiKey: String): String {
        val audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val parts = JSONArray().apply {
            put(JSONObject().apply {
                put(
                    "text",
                    "Transcribeer deze korte Nederlandse spraakopname exact. " +
                        "Geef alleen de gesproken tekst terug, zonder uitleg, aanhalingstekens of opmaak."
                )
            })
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
                put("maxOutputTokens", 384)
            })
        }

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 18_000
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
        val candidates = json.optJSONArray("candidates")
            ?: throw Exception("geen transcriptie ontvangen")
        if (candidates.length() == 0) throw Exception("geen transcriptie ontvangen")
        val content = candidates.getJSONObject(0).optJSONObject("content")
            ?: throw Exception("lege transcriptie")
        val responseParts = content.optJSONArray("parts")
            ?: throw Exception("lege transcriptie")
        if (responseParts.length() == 0) throw Exception("lege transcriptie")
        return responseParts.getJSONObject(0).optString("text").trim()
    }
}
