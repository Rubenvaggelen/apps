package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stuurt vragen vanuit "Vraag het" en Recepten rechtstreeks naar de OpenAI
 * Responses API. De Android-app gebruikt één project/API-key die tijdens de
 * GitHub-build via BuildConfig wordt geïnjecteerd.
 */
object ChatGptClient {

    private const val TAG = "AskAi"
    private const val MODEL = "gpt-5-mini"
    private const val RESPONSES_URL = "https://api.openai.com/v1/responses"

    sealed class AskOutcome {
        data class Success(val answer: String) : AskOutcome()
        data class Error(val message: String) : AskOutcome()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun ask(question: String, callback: (AskOutcome) -> Unit) {
        val apiKey = BuildConfig.OPENAI_API_KEY.trim()
        if (apiKey.isBlank() || apiKey.startsWith("PLAATS_HIER")) {
            callback(
                AskOutcome.Error(
                    "Geen OpenAI API-key ingesteld. Voeg in GitHub Actions de secret OPENAI_API_KEY toe."
                )
            )
            return
        }

        Thread({
            try {
                val answer = fetchAnswer(question, apiKey)
                mainHandler.post { callback(AskOutcome.Success(answer)) }
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI-vraag mislukt", e)
                val message = e.message ?: "onbekende fout"
                mainHandler.post {
                    callback(AskOutcome.Error("ChatGPT kon niet antwoorden: $message"))
                }
            }
        }, "TheOne-OpenAI").start()
    }

    private fun fetchAnswer(question: String, apiKey: String): String {
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put(
                "instructions",
                "Antwoord altijd in het Nederlands, tenzij de gebruiker expliciet om een andere taal vraagt. " +
                    "Geef een praktisch, duidelijk antwoord. Volg gevraagde formats exact, bijvoorbeeld bij recepten."
            )
            put("input", question)
            put("max_output_tokens", 2200)
        }

        val connection = (URL(RESPONSES_URL).openConnection() as HttpURLConnection).apply {
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
                401 -> "de OpenAI API-key is ongeldig"
                429 -> "de OpenAI API-limiet of het beschikbare API-tegoed is bereikt"
                else -> apiMessage.ifBlank { "OpenAI HTTP $responseCode" }
            }
            throw Exception(friendly)
        }

        // Sommige Responses-API versies leveren een top-level output_text.
        json.optString("output_text").trim().takeIf { it.isNotBlank() }?.let { return it }

        // Standaard Responses-API vorm: output[] -> message -> content[] -> output_text.
        val collected = mutableListOf<String>()
        val output = json.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    val text = part.optString("text").trim()
                    if (text.isNotBlank()) collected += text
                }
            }
        }

        val answer = collected.joinToString("\n").trim()
        if (answer.isBlank()) throw Exception("OpenAI gaf geen antwoordtekst terug")
        return answer
    }
}
