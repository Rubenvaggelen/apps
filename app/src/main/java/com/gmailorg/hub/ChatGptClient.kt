package com.gmailorg.hub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI-client voor Vraag het en Recepten. Gebruikt Groq vanaf de telefoon met
 * een lokaal versleuteld opgeslagen API-key.
 */
object ChatGptClient {

    private const val TAG = "GroqAi"
    private const val MODEL = "llama-3.3-70b-versatile"
    private const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"

    sealed class AskOutcome {
        data class Success(val answer: String) : AskOutcome()
        data class Error(val message: String) : AskOutcome()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun ask(context: Context, question: String, callback: (AskOutcome) -> Unit) {
        val apiKey = GroqApiKeyStore.get(context)
        if (apiKey.isBlank()) {
            callback(
                AskOutcome.Error(
                    "Groq is nog niet gekoppeld. Open Instellingen en vul daar één keer je Groq API-key in."
                )
            )
            return
        }

        Thread({
            try {
                val answer = fetchAnswer(question, apiKey)
                mainHandler.post { callback(AskOutcome.Success(answer)) }
            } catch (e: Exception) {
                Log.e(TAG, "Groq-vraag mislukt", e)
                val message = e.message ?: "onbekende fout"
                mainHandler.post { callback(AskOutcome.Error("AI kon niet antwoorden: $message")) }
            }
        }, "TheOne-Groq").start()
    }

    private fun fetchAnswer(question: String, apiKey: String): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "Antwoord altijd in het Nederlands, tenzij de gebruiker expliciet om een andere taal vraagt. " +
                        "Geef een praktisch, duidelijk antwoord en volg gevraagde formats exact."
                )
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", question)
            })
        }
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("temperature", 0.35)
        }

        val connection = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 45_000
        }
        connection.outputStream.use { output ->
            output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()

        val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
        if (responseCode !in 200..299) {
            val apiMessage = json.optJSONObject("error")?.optString("message").orEmpty()
            val friendly = when (responseCode) {
                401 -> "de Groq API-key is ongeldig"
                429 -> "de Groq API-limiet is bereikt; probeer het later opnieuw"
                else -> apiMessage.ifBlank { "Groq HTTP $responseCode" }
            }
            throw Exception(friendly)
        }

        val choices = json.optJSONArray("choices") ?: JSONArray()
        val answer = choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (answer.isBlank()) throw Exception("Groq gaf geen antwoordtekst terug")
        return answer
    }
}
