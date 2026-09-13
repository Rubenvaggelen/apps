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
 * Groq AI-client voor Vraag het en Recepten.
 *
 * - Vraag het gebruikt actieve Groq-modellen en valt automatisch terug op een
 *   tweede model als het eerste model niet beschikbaar is of zijn model-limiet raakt.
 * - Recepten gebruikt Groq Compound met live web search/visit_website en beperkt
 *   de zoekresultaten tot de door de gebruiker gekozen receptbronnen.
 */
object ChatGptClient {

    private const val TAG = "GroqAi"
    private const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"

    private val QUESTION_MODELS = listOf(
        "groq/compound-mini",
        "openai/gpt-oss-20b",
        "qwen/qwen3.6-27b"
    )

    private val RECIPE_SYSTEMS = listOf(
        "groq/compound",
        "groq/compound-mini"
    )

    sealed class AskOutcome {
        data class Success(val answer: String) : AskOutcome()
        data class Error(val message: String) : AskOutcome()
    }

    private class GroqHttpException(
        val statusCode: Int,
        val retryAfterSeconds: Long?,
        message: String
    ) : Exception(message)

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
                val answer = fetchQuestionWithFallback(question, apiKey)
                mainHandler.post { callback(AskOutcome.Success(answer)) }
            } catch (e: Exception) {
                Log.e(TAG, "Groq-vraag mislukt", e)
                val message = e.message ?: "onbekende fout"
                mainHandler.post { callback(AskOutcome.Error("AI kon niet antwoorden: $message")) }
            }
        }, "TheOne-Groq").start()
    }

    /**
     * Zoekt live op de ingestelde receptsites en geeft meteen één recept terug.
     * De gebruiker hoeft dus nooit uit meerdere resultaten te kiezen.
     */
    fun askRecipe(context: Context, dish: String, callback: (AskOutcome) -> Unit) {
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
                val answer = fetchRecipeWithWebSearch(dish, apiKey)
                mainHandler.post { callback(AskOutcome.Success(answer)) }
            } catch (e: Exception) {
                Log.e(TAG, "Groq-receptzoekactie mislukt", e)
                val message = e.message ?: "onbekende fout"
                mainHandler.post { callback(AskOutcome.Error("Recept kon niet worden opgezocht: $message")) }
            }
        }, "TheOne-Groq-Recipe").start()
    }

    private fun fetchQuestionWithFallback(question: String, apiKey: String): String {
        var lastError: Exception? = null
        QUESTION_MODELS.forEachIndexed { index, model ->
            try {
                return fetchChatAnswer(question, apiKey, model)
            } catch (e: GroqHttpException) {
                lastError = e

                // Een korte TPM/RPM-limiet kan al na een paar seconden weg zijn.
                // Wacht één keer heel kort en probeer hetzelfde model opnieuw.
                if (e.statusCode == 429 && e.retryAfterSeconds != null && e.retryAfterSeconds in 1..8) {
                    try {
                        Thread.sleep(e.retryAfterSeconds * 1000L)
                        return fetchChatAnswer(question, apiKey, model)
                    } catch (retry: GroqHttpException) {
                        lastError = retry
                    }
                }

                val mayFallback = e.statusCode == 400 || e.statusCode == 404 || e.statusCode == 429
                if (!mayFallback || index == QUESTION_MODELS.lastIndex) throw friendlyFinalError(lastError as? GroqHttpException ?: e)
                Log.w(TAG, "Model $model niet bruikbaar (${e.statusCode}); probeer fallback-model")
            }
        }
        throw lastError ?: Exception("Groq gaf geen antwoord")
    }

    private fun fetchChatAnswer(question: String, apiKey: String, model: String): String {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "Antwoord in het Nederlands, tenzij expliciet een andere taal wordt gevraagd. " +
                        "Geef alleen het antwoord, nooit analyse of thinking-tags. Houd een simpel antwoord kort; " +
                        "geef alleen meer detail als de vraag dat nodig heeft."
                )
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", question)
            })
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.25)
            put("max_completion_tokens", 420)

            // Belangrijk voor de gratis Groq-limieten: geen verborgen lange
            // redeneerketens genereren. Die tellen alsnog mee voor TPM.
            if (model.startsWith("qwen/")) {
                put("reasoning_effort", "none")
            } else if (model.startsWith("openai/gpt-oss")) {
                put("reasoning_effort", "low")
                put("include_reasoning", false)
            }
        }

        return sanitizeFinalAnswer(
            executeChatRequest(
                apiKey,
                requestBody,
                useLatestCompoundVersion = model.startsWith("groq/compound")
            )
        )
    }

    private fun fetchRecipeWithWebSearch(dish: String, apiKey: String): String {
        val prompt = """
            Zoek live op internet naar een recept voor: $dish.

            Zoek uitsluitend binnen deze voorkeursbronnen:
            1. https://sranangkukru.net/recepten/
            2. https://www.leukerecepten.nl/italiaanse-recepten/
            3. https://www.leukerecepten.nl/hollandse-recepten/

            Belangrijk:
            - Probeer de relevante pagina's van deze bronnen daadwerkelijk te zoeken/bezoeken.
            - Als meerdere bronnen een passend recept hebben, kies ZELF de beste match.
            - Toon maar ÉÉN compleet recept. Vraag de gebruiker NIET om een bron of recept te kiezen.
            - Gebruik geen bron buiten de drie genoemde domeinen.
            - Baseer ingrediënten en bereidingswijze zo veel mogelijk op het gevonden recept en verzin geen citaat.
            - Antwoord in het Nederlands.

            Gebruik EXACT dit format:
            BRON:
            [naam van gebruikte website] — [volledige URL van het gebruikte recept of de best passende bronpagina]
            INGREDIENTEN:
            - [hoeveelheid] [ingrediënt]
            - ... (één ingrediënt per regel, voor ongeveer 4 personen)
            BEREIDING:
            1. [eerste stap]
            2. ... (genummerde stappen)
            BOODSCHAPPENLIJST:
            - [alleen ingrediëntnaam, zonder hoeveelheid of maateenheid]
            - ...
        """.trimIndent()

        var lastError: Exception? = null
        RECIPE_SYSTEMS.forEachIndexed { index, system ->
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
                val requestBody = JSONObject().apply {
                    put("model", system)
                    put("messages", messages)
                    put(
                        "search_settings",
                        JSONObject().apply {
                            put(
                                "include_domains",
                                JSONArray().apply {
                                    put("sranangkukru.net")
                                    put("leukerecepten.nl")
                                }
                            )
                            put("country", "netherlands")
                        }
                    )
                    put(
                        "compound_custom",
                        JSONObject().apply {
                            put(
                                "tools",
                                JSONObject().apply {
                                    put(
                                        "enabled_tools",
                                        JSONArray().apply {
                                            put("web_search")
                                            put("visit_website")
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
                return sanitizeFinalAnswer(
                    executeChatRequest(apiKey, requestBody, useLatestCompoundVersion = true)
                )
            } catch (e: GroqHttpException) {
                lastError = e
                val mayFallback = e.statusCode == 400 || e.statusCode == 404 || e.statusCode == 429
                if (!mayFallback || index == RECIPE_SYSTEMS.lastIndex) throw friendlyFinalError(e)
                Log.w(TAG, "Receptensysteem $system niet bruikbaar (${e.statusCode}); probeer fallback")
            }
        }
        // Als live websearch tijdelijk zijn eigen limiet raakt, geef de gebruiker
        // alsnog één recept via de gewone Groq-modellen. De bronzoekactie blijft
        // de voorkeursroute, maar een rate-limit mag het receptenscherm niet
        // volledig blokkeren.
        val fallbackPrompt = """
            Geef precies één compleet recept voor: $dish.
            Antwoord in het Nederlands. Geef geen keuzes en stel geen wedervraag.
            Gebruik exact deze secties:
            INGREDIENTEN:
            - [hoeveelheid] [ingrediënt]
            BEREIDING:
            1. [stap]
            BOODSCHAPPENLIJST:
            - [alleen ingrediëntnaam]
        """.trimIndent()
        return try {
            fetchQuestionWithFallback(fallbackPrompt, apiKey)
        } catch (fallback: Exception) {
            throw lastError ?: fallback
        }
    }

    private fun executeChatRequest(
        apiKey: String,
        requestBody: JSONObject,
        useLatestCompoundVersion: Boolean
    ): String {
        val connection = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            if (useLatestCompoundVersion) {
                setRequestProperty("Groq-Model-Version", "latest")
            }
            doOutput = true
            connectTimeout = 12_000
            readTimeout = if (useLatestCompoundVersion) 75_000 else 45_000
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
                401 -> "de Groq API-key is ongeldig"
                429 -> if (retryAfter != null) {
                    "de Groq API-limiet is bereikt; probeer over ongeveer $retryAfter seconden opnieuw"
                } else {
                    "de Groq API-limiet is bereikt; probeer het later opnieuw"
                }
                else -> apiMessage.ifBlank { "Groq HTTP $responseCode" }
            }
            throw GroqHttpException(responseCode, retryAfter, friendly)
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

    /**
     * Extra vangnet: sommige modellen kunnen ondanks instellingen nog een
     * <think>...</think>-blok terugsturen. De gebruiker hoort alleen het
     * uiteindelijke antwoord te zien.
     */
    private fun sanitizeFinalAnswer(raw: String): String {
        var text = raw.trim()

        // Verwijder volledige thinking-blokken (ook over meerdere regels).
        text = text.replace(
            Regex("(?is)<think>.*?</think>\\s*"),
            ""
        ).trim()

        // Als een provider alleen een openings-tag teruggeeft, pak dan bij
        // voorkeur tekst na een herkenbare final-answer markering.
        if (text.contains("<think>", ignoreCase = true)) {
            val markers = listOf("</think>", "Final answer:", "Final:", "Antwoord:")
            val marker = markers
                .mapNotNull { m -> text.indexOf(m, ignoreCase = true).takeIf { it >= 0 }?.let { it to m.length } }
                .maxByOrNull { it.first }
            if (marker != null) {
                text = text.substring(marker.first + marker.second).trim()
            } else {
                text = text.replace(Regex("(?is)<think>.*"), "").trim()
            }
        }

        return text
            .removePrefix("Final answer:").removePrefix("Final:").removePrefix("Antwoord:")
            .trim()
            .ifBlank { "Ik kreeg geen bruikbaar antwoord terug. Probeer het nog een keer." }
    }

    private fun friendlyFinalError(error: GroqHttpException): Exception {
        return if (error.statusCode == 429 && error.retryAfterSeconds != null) {
            Exception("de Groq API-limiet is bereikt; probeer over ongeveer ${error.retryAfterSeconds} seconden opnieuw")
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
