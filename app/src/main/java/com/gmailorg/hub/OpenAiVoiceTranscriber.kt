package com.gmailorg.hub

import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Stuurt de VOLLEDIGE WAV-opname in één request naar OpenAI Speech-to-Text.
 * Daardoor kan een langer WhatsApp-antwoord niet meer door een lokale
 * SpeechRecognizer na het eerste segment worden afgekapt.
 */
object OpenAiVoiceTranscriber {
    private const val TAG = "OpenAiVoice"
    private const val MODEL = "gpt-4o-mini-transcribe"
    private const val TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions"

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    fun transcribe(wavBytes: ByteArray, callback: (Result) -> Unit) {
        val apiKey = BuildConfig.OPENAI_API_KEY.trim()
        if (apiKey.isBlank() || apiKey.startsWith("PLAATS_HIER")) {
            callback(Result.Error("OpenAI API-key ontbreekt op de telefoon"))
            return
        }
        if (wavBytes.size < 1000) {
            callback(Result.Error("lege of ongeldige audio-opname"))
            return
        }

        Thread({
            try {
                val text = requestTranscription(wavBytes, apiKey)
                if (text.isBlank()) callback(Result.Error("geen spraak herkend"))
                else callback(Result.Success(text))
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI transcriptie mislukt", e)
                callback(Result.Error(e.message ?: e.javaClass.simpleName))
            }
        }, "TheOne-OpenAI-Voice").start()
    }

    private fun requestTranscription(wavBytes: ByteArray, apiKey: String): String {
        val boundary = "----TheOne${UUID.randomUUID()}"
        val connection = (URL(TRANSCRIBE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
            useCaches = false
            connectTimeout = 10_000
            readTimeout = 45_000
        }

        DataOutputStream(connection.outputStream).use { out ->
            writeField(out, boundary, "model", MODEL)
            writeField(out, boundary, "language", "nl")
            writeField(out, boundary, "response_format", "json")
            writeField(
                out,
                boundary,
                "prompt",
                "Dit is één volledig Nederlands WhatsApp-antwoord. Schrijf alle gesproken woorden van begin tot einde uit, ook na korte pauzes."
            )

            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"the_one_voice.wav\"\r\n")
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            out.write(wavBytes)
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()

        val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
        if (code !in 200..299) {
            val apiMessage = json.optJSONObject("error")?.optString("message").orEmpty()
            val friendly = when (code) {
                401 -> "OpenAI API-key ongeldig"
                429 -> "OpenAI API-limiet of API-tegoed bereikt"
                else -> apiMessage.ifBlank { "OpenAI HTTP $code" }
            }
            throw Exception(friendly)
        }
        return json.optString("text").trim()
    }

    private fun writeField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }
}
