package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stuurt een vraag naar Google's Gemini-API (gratis niveau, geen creditcard
 * nodig) en geeft alleen het antwoordtekstje terug — voor de "Vraag
 * het"-tegel.
 */
object ChatGptClient {

    private const val TAG = "AskAi"
    private const val MODEL = "gemini-3.6-flash"

    sealed class AskOutcome {
        data class Success(val answer: String) : AskOutcome()
        data class Error(val message: String) : AskOutcome()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun ask(question: String, callback: (AskOutcome) -> Unit) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            callback(
                AskOutcome.Error(
                    "Geen Gemini API key ingesteld. Zet je key in gradle.properties (GEMINI_API_KEY)."
                )
            )
            return
        }

        Thread {
            try {
                val answer = fetchAnswer(question, apiKey)
                mainHandler.post { callback(AskOutcome.Success(answer)) }
            } catch (e: Exception) {
                Log.e(TAG, "Vraag stellen mislukt", e)
                mainHandler.post {
                    callback(AskOutcome.Error("Vraag stellen mislukt: ${e.message ?: "onbekende fout"}"))
                }
            }
        }.start()
    }

    private fun fetchAnswer(question: String, apiKey: String): String {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("parts", JSONArray().put(
                        JSONObject().apply { put("text", question) }
                    ))
                }
            ))
        }

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 20000
        connection.readTimeout = 30000
        connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(body)
        if (responseCode !in 200..299) {
            val errorMessage = json.optJSONObject("error")?.optString("message") ?: "HTTP $responseCode"
            throw Exception(errorMessage)
        }

        val candidates = json.getJSONArray("candidates")
        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.getJSONArray("parts")
        return parts.getJSONObject(0).getString("text").trim()
    }
}
