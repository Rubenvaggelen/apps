package com.gmailorg.hub

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Stuurt de volledige WAV in één keer naar Groq Whisper Turbo. */
object GroqVoiceTranscriber {
    private const val TAG = "GroqVoice"
    private const val MODEL = "whisper-large-v3"
    private const val TRANSCRIBE_URL = "https://api.groq.com/openai/v1/audio/transcriptions"

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    fun transcribe(context: Context, wavBytes: ByteArray, callback: (Result) -> Unit) {
        val apiKey = GroqApiKeyStore.get(context)
        if (apiKey.isBlank()) {
            callback(Result.Error("Groq API-key ontbreekt op de telefoon"))
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
                Log.e(TAG, "Groq transcriptie mislukt", e)
                callback(Result.Error(e.message ?: e.javaClass.simpleName))
            }
        }, "TheOne-Groq-Voice").start()
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
            writeField(out, boundary, "temperature", "0")
            writeField(
                out,
                boundary,
                "prompt",
                "Dit is een Nederlands WhatsApp-antwoord dat in een auto is ingesproken. Transcribeer letterlijk en volledig van begin tot einde. Let extra op Nederlandse namen, straatnamen en gewone spreektaal. Sla geen woorden over en vul niets zelf aan."
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
                401 -> "Groq API-key ongeldig"
                413 -> "spraakopname is te groot om te verwerken"
                429 -> "Groq API-limiet bereikt; probeer het later opnieuw"
                else -> apiMessage.ifBlank { "Groq HTTP $code" }
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
