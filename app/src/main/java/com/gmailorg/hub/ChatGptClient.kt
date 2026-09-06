package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stuurt een vraag naar OpenAI's ChatGPT-API en geeft alleen het
 * antwoordtekstje terug — voor de "Vraag het"-tegel.
 */
object ChatGptClient {

    private const val TAG = "ChatGptClient"
    private const val MODEL = "gpt-4o-mini"

    sealed class AskOutcome {
        data class Success(val answer: String) : AskOutcome()
        data class Error(val message: String) : AskOutcome()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun ask(question: String, callback: (AskOutcome) -> Unit) {
        val apiKey = BuildConfig.OPENAI_API_KEY
        if (apiKey.isBlank()) {
            callback(
                AskOutcome.Error(
                    "Geen OpenAI API key ingesteld. Zet je key in gradle.properties (OPENAI_API_KEY)."
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
            put("model", MODEL)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", question)
                }
            ))
        }

        val connection = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
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

        val choices = json.getJSONArray("choices")
        val message = choices.getJSONObject(0).getJSONObject("message")
        return message.getString("content").trim()
    }
}
