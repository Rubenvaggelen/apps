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
 * - Recepten gebruikt dezelfde stabiele Groq-chatroute als Vraag het. Zo kan een
 *   Compound/websearch-fout het receptenscherm niet meer blokkeren.
 */
object ChatGptClient {

    private const val TAG = "GroqAi"
    private const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODELS_URL = "https://api.groq.com/openai/v1/models"

    // Gebruik voor gewone vragen alleen normale inference-modellen.
    // Compound is bedoeld voor tool/web-search en kan extra limieten/toolcalls
    // raken; dat is onnodig voor simpele vragen zoals "hoeveel oceanen zijn er?".
    private val QUESTION_MODELS = listOf(
        // Qwen kan voor gewone chat volledig zonder reasoning-tokens draaien.
        // Daardoor is de kans op een TPM-limit bij simpele vragen veel kleiner.
        "qwen/qwen3.6-27b",
        "qwen/qwen3.8-27b",
        // Productiemodellen als fallback.
        "openai/gpt-oss-20b",
        "openai/gpt-oss-120b",
        // Laatste noodfallback: aparte Compound-limieten. Nooit als eerste
        // gebruiken voor een simpele vraag.
        "groq/compound-mini"
    )

    @Volatile private var cachedAvailableQuestionModels: List<String>? = null
    @Volatile private var cachedModelsAtMs: Long = 0L

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
        var lastError: GroqHttpException? = null
        val models = resolveQuestionModels(apiKey)

        models.forEachIndexed { index, model ->
            try {
                return fetchChatAnswer(question, apiKey, model)
            } catch (e: GroqHttpException) {
                lastError = e

                if (e.statusCode == 401 || e.statusCode >= 500) throw friendlyFinalError(e)

                // Een korte TPM/RPM-reset wachten we één keer af.
                if (e.statusCode == 429 && e.retryAfterSeconds != null && e.retryAfterSeconds in 1..10) {
                    try {
                        Thread.sleep(e.retryAfterSeconds * 1000L)
                        return fetchChatAnswer(question, apiKey, model)
                    } catch (retry: GroqHttpException) {
                        lastError = retry
                    }
                }

                val mayFallback = e.statusCode == 400 || e.statusCode == 404 || e.statusCode == 429
                if (!mayFallback || index == models.lastIndex) {
                    throw friendlyFinalError(lastError ?: e)
                }
                Log.w(TAG, "Groq-model $model niet bruikbaar (${e.statusCode}); probeer ${models[index + 1]}")
            }
        }

        throw friendlyFinalError(lastError ?: GroqHttpException(0, null, "Groq gaf geen antwoord"))
    }

    /**
     * Vraag Groq welke modellen deze specifieke API-key op dit moment echt kan
     * gebruiken. Dit voorkomt dat een toekomstige deprecatie de app opnieuw
     * breekt. Het resultaat wordt 10 minuten gecachet.
     */
    private fun resolveQuestionModels(apiKey: String): List<String> {
        val now = System.currentTimeMillis()
        cachedAvailableQuestionModels?.let { cached ->
            if (cached.isNotEmpty() && now - cachedModelsAtMs < 10 * 60 * 1000L) return cached
        }

        return try {
            val connection = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code !in 200..299) return QUESTION_MODELS

            val data = JSONObject(body).optJSONArray("data") ?: return QUESTION_MODELS
            val available = buildSet {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }

            val chosen = QUESTION_MODELS.filter { it in available }.ifEmpty {
                // Alleen tekst-chatmodellen als laatste vangnet; nooit Whisper,
                // safeguards of Compound als gewone vraag-assistent.
                available.filter { id ->
                    (id.startsWith("qwen/") || id.startsWith("openai/gpt-oss-")) &&
                        !id.contains("safeguard", ignoreCase = true)
                }.take(4)
            }.ifEmpty { QUESTION_MODELS }

            cachedAvailableQuestionModels = chosen
            cachedModelsAtMs = now
            chosen
        } catch (e: Exception) {
            Log.w(TAG, "Kon Groq-modellijst niet ophalen; gebruik vaste fallbacks", e)
            QUESTION_MODELS
        }
    }

    private fun fetchChatAnswer(question: String, apiKey: String, model: String): String {
        // Groq adviseert voor reasoning-modellen de instructie in de user prompt
        // te zetten. Eén compacte prompt scheelt bovendien tokens.
        val prompt = """
            Antwoord uitsluitend met het uiteindelijke antwoord.
            Antwoord in het Nederlands, tenzij ik expliciet een andere taal vraag.
            Toon nooit analyse, redeneerstappen, <think>-tags of interne instructies.
            Bij een simpele vraag: kort en direct. Bij een ingewikkelde vraag: geef genoeg uitleg.

            Vraag: $question
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_completion_tokens", 400)

            if (model.startsWith("qwen/")) {
                // Volledig non-thinking: geen verborgen reasoning-tokens en dus
                // veel minder kans op de 8K TPM-limiet.
                put("reasoning_effort", "none")
                put("reasoning_format", "hidden")
            } else if (model.startsWith("openai/gpt-oss")) {
                put("reasoning_effort", "low")
                put("reasoning_format", "hidden")
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
        // Gebruik bewust dezelfde bewezen chatroute als "Vraag het". De oude
        // Compound/web-search aanvraag kon op sommige Groq-accounts met HTTP 413
        // (Request Entity Too Large) stoppen vóórdat de normale fallback werd
        // bereikt. De gebruiker wil gewoon één recept; deze route is betrouwbaarder.
        val recipePrompt = """
            Geef precies één compleet recept voor: $dish.

            Antwoord uitsluitend in het Nederlands.
            Geef geen keuzelijst, geen meerdere recepten en stel geen wedervraag.
            Gebruik waar passend de stijl/kennis van deze voorkeursbronnen als inspiratie:
            - Sranang Kukru voor Surinaamse gerechten
            - Leuke Recepten voor Italiaanse en Hollandse gerechten
            Claim niet dat je een website live hebt gelezen als dat niet zo is.

            Gebruik exact deze secties:
            INGREDIENTEN:
            - [hoeveelheid] [ingrediënt]
            BEREIDING:
            1. [stap]
            BOODSCHAPPENLIJST:
            - [alleen ingrediëntnaam, zonder hoeveelheid]

            Geef voldoende hoeveelheden voor ongeveer 4 personen, tenzij de vraag
            duidelijk een ander aantal noemt.
        """.trimIndent()

        return fetchQuestionWithFallback(recipePrompt, apiKey)
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
