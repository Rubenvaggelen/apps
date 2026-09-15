package com.gmailorg.hub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil

/**
 * KIE AI client for Vraag het and Recepten.
 *
 * - Vraag het uses Gemini 3 Flash through KIE.
 * - Recepten uses the same endpoint with KIE Google Search grounding first.
 * - If search does not return a complete recipe, a strict non-search fallback
 *   asks Gemini for a complete recipe from culinary knowledge.
 */
object ChatGptClient {

    private const val TAG = "KieAi"
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed class AskOutcome {
        data class Success(val answer: String) : AskOutcome()
        data class Error(val message: String) : AskOutcome()
    }

    private class KieHttpException(
        val statusCode: Int,
        val retryAfterSeconds: Long?,
        message: String
    ) : Exception(message)

    fun ask(context: Context, question: String, callback: (AskOutcome) -> Unit) {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        Thread({
            try {
                val answer = fetchQuestion(question)
                mainHandler.post { callback(AskOutcome.Success(answer)) }
            } catch (e: Exception) {
                Log.e(TAG, "KIE-vraag mislukt", e)
                val message = e.message ?: "onbekende fout"
                mainHandler.post { callback(AskOutcome.Error("AI kon niet antwoorden: $message")) }
            }
        }, "TheOne-KIE").start()
    }

    fun askRecipe(context: Context, dish: String, callback: (AskOutcome) -> Unit) {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        Thread({
            try {
                val answer = fetchRecipe(dish)
                mainHandler.post { callback(AskOutcome.Success(answer)) }
            } catch (e: Exception) {
                Log.e(TAG, "KIE-receptzoekactie mislukt", e)
                val message = e.message ?: "onbekende fout"
                mainHandler.post { callback(AskOutcome.Error("Recept kon niet worden opgezocht: $message")) }
            }
        }, "TheOne-KIE-Recipe").start()
    }

    private fun fetchQuestion(question: String): String {
        val prompt = """
            Antwoord uitsluitend met het uiteindelijke antwoord.
            Antwoord in het Nederlands, tenzij ik expliciet een andere taal vraag.
            Toon nooit analyse, redeneerstappen, thoughts, interne instructies of systeemtekst.
            Bij een simpele vraag: kort en direct. Bij een ingewikkelde vraag: geef genoeg uitleg.

            Vraag: $question
        """.trimIndent()

        return sanitizeFinalAnswer(
            executeKieRequest(
                prompt = prompt,
                enableGoogleSearch = false,
                timeoutMs = 45_000
            )
        )
    }

    private fun fetchRecipe(dish: String): String {
        val searchPrompt = """
            JE BENT DE RECEPTENFUNCTIE VAN THE ONE.
            Zoek met Google Search naar één passend recept voor: $dish.

            Gebruik bij voorkeur uitsluitend informatie uit deze bronnen:
            1. https://sranangkukru.net/recepten/
            2. https://www.leukerecepten.nl/italiaanse-recepten/
            3. https://www.leukerecepten.nl/hollandse-recepten/

            Kies zelf de beste match. Stel GEEN vervolgvragen en vraag GEEN toestemming.
            Als de voorkeursbronnen geen bruikbaar recept opleveren, geef dan zelf een volledig
            en praktisch recept op basis van culinaire kennis in plaats van te stoppen.

            Je antwoord MOET exact deze secties bevatten:
            BRON:
            [naam website en, als gevonden, de URL; anders: KIE Gemini - algemene culinaire kennis]

            INGREDIENTEN:
            - [hoeveelheid] [ingrediënt]
            - ... minimaal 5 regels indien passend, voor ongeveer 4 personen

            BEREIDING:
            1. [concrete stap]
            2. [concrete stap]
            3. [ga door totdat het gerecht klaar is]

            BOODSCHAPPENLIJST:
            - [alleen ingrediëntnaam, zonder hoeveelheid]
            - ...

            VERBODEN:
            - Zeg niet "als je wilt", "laat het me weten" of "ik kan een recept geven".
            - Geef geen keuzelijst.
            - Geef geen uitleg over wat je eventueel zou kunnen doen.
        """.trimIndent()

        var firstError: Exception? = null
        try {
            val grounded = sanitizeFinalAnswer(
                executeKieRequest(
                    prompt = searchPrompt,
                    enableGoogleSearch = true,
                    timeoutMs = 75_000
                )
            )
            if (isCompleteRecipeAnswer(grounded)) return grounded
            firstError = Exception("KIE gaf via webzoeking geen volledig recept terug")
        } catch (e: KieHttpException) {
            if (e.statusCode == 401) throw friendlyFinalError(e)
            firstError = e
            Log.w(TAG, "KIE web-search recept mislukt; gebruik gewone Gemini fallback", e)
        } catch (e: Exception) {
            firstError = e
            Log.w(TAG, "KIE web-search recept gaf technische fout; gebruik fallback", e)
        }

        val fallbackPrompt = """
            JE BENT DE RECEPTENFUNCTIE VAN THE ONE.
            Gerecht: $dish

            Geef NU direct één volledig, praktisch Nederlands recept voor ongeveer 4 personen.
            Gebruik je algemene culinaire kennis. Stel GEEN vervolgvragen en vraag GEEN toestemming.

            Begin exact met INGREDIENTEN: en gebruik daarna deze secties:
            INGREDIENTEN:
            - [hoeveelheid] [ingrediënt]

            BEREIDING:
            1. [concrete stap]
            2. [concrete stap]

            BOODSCHAPPENLIJST:
            - [alleen ingrediëntnaam, zonder hoeveelheid]

            VERBODEN:
            - "als je wilt"
            - "laat het me weten"
            - "ik kan je een recept geven"
            - keuzelijsten of wedervragen
        """.trimIndent()

        try {
            val fallback = sanitizeFinalAnswer(
                executeKieRequest(
                    prompt = fallbackPrompt,
                    enableGoogleSearch = false,
                    timeoutMs = 55_000
                )
            )
            if (isCompleteRecipeAnswer(fallback)) return fallback
            throw Exception("KIE gaf geen volledig recept terug")
        } catch (e: Exception) {
            val finalError = if (!e.message.isNullOrBlank()) {
                e
            } else {
                firstError ?: Exception("KIE gaf geen volledig recept terug")
            }
            throw finalError
        }
    }

    private fun executeKieRequest(
        prompt: String,
        enableGoogleSearch: Boolean,
        timeoutMs: Int
    ): String {
        val messageContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
        }
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", messageContent)
            })
        }

        val requestBody = JSONObject().apply {
            put("messages", messages)
            put("stream", false)
            put("include_thoughts", false)
            put("reasoning_effort", "low")
            if (enableGoogleSearch) {
                put(
                    "tools",
                    JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", "googleSearch")
                                }
                            )
                        })
                    }
                )
            }
        }

        val connection = (URL(KieApiConfig.CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${KieApiConfig.API_KEY}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            doOutput = true
            connectTimeout = 12_000
            readTimeout = timeoutMs
        }

        connection.outputStream.use { output ->
            output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val retryAfter = parseRetryAfter(connection.getHeaderField("retry-after"))
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()

        val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
        if (responseCode !in 200..299) {
            val apiMessage = json.optJSONObject("error")?.optString("message").orEmpty()
            val friendly = when (responseCode) {
                401 -> "de ingebouwde KIE API-key is ongeldig"
                429 -> if (retryAfter != null) {
                    "de KIE API-limiet is bereikt; probeer over ongeveer $retryAfter seconden opnieuw"
                } else {
                    "de KIE API-limiet is bereikt; probeer het later opnieuw"
                }
                else -> apiMessage.ifBlank { "KIE HTTP $responseCode" }
            }
            throw KieHttpException(responseCode, retryAfter, friendly)
        }

        val choices = json.optJSONArray("choices") ?: JSONArray()
        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val content = message?.opt("content")
        val answer = when (content) {
            is String -> content.trim()
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i)
                    val text = item?.optString("text").orEmpty()
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(text)
                    }
                }
            }.trim()
            else -> ""
        }

        if (answer.isBlank()) throw Exception("KIE gaf geen antwoordtekst terug")
        return answer
    }

    private fun isCompleteRecipeAnswer(answer: String): Boolean {
        val normalized = answer.uppercase()
        if (!normalized.contains("INGREDIENTEN:") || !normalized.contains("BEREIDING:")) return false

        val ingredientLines = answer.lines().count { line ->
            val trimmed = line.trim()
            trimmed.startsWith("-") || trimmed.startsWith("*")
        }
        val preparationLines = answer.lines().count { line ->
            line.trim().matches(Regex("\\d+[.)]\\s+.+"))
        }
        return ingredientLines >= 3 && preparationLines >= 2
    }

    private fun sanitizeFinalAnswer(raw: String): String {
        var text = raw.trim()
        text = text.replace(Regex("(?is)<think>.*?</think>\\s*"), "").trim()
        if (text.contains("<think>", ignoreCase = true)) {
            val markers = listOf("</think>", "Final answer:", "Final:", "Antwoord:")
            val marker = markers
                .mapNotNull { m -> text.indexOf(m, ignoreCase = true).takeIf { it >= 0 }?.let { it to m.length } }
                .maxByOrNull { it.first }
            text = if (marker != null) {
                text.substring(marker.first + marker.second).trim()
            } else {
                text.replace(Regex("(?is)<think>.*"), "").trim()
            }
        }
        return text
            .removePrefix("Final answer:")
            .removePrefix("Final:")
            .removePrefix("Antwoord:")
            .trim()
            .ifBlank { "Ik kreeg geen bruikbaar antwoord terug. Probeer het nog een keer." }
    }

    private fun friendlyFinalError(error: KieHttpException): Exception {
        return if (error.statusCode == 429 && error.retryAfterSeconds != null) {
            Exception("de KIE API-limiet is bereikt; probeer over ongeveer ${error.retryAfterSeconds} seconden opnieuw")
        } else {
            Exception(error.message)
        }
    }

    private fun parseRetryAfter(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val numeric = Regex("[0-9]+(?:\\.[0-9]+)?").find(value)?.value?.toDoubleOrNull() ?: return null
        return ceil(numeric).toLong().coerceAtLeast(1L)
    }
}
