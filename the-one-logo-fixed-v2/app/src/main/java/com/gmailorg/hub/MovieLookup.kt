package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Zoekt een titel op via TMDB (films én series, via het "multi search"-
 * endpoint) en kijkt via het "watch/providers"-endpoint op welke
 * streamingdiensten (regio NL) hij te vinden is. Gebruikt voor de
 * "Films zoeken"-sectie onder Huishouden.
 */
object MovieLookup {

    private const val TAG = "MovieLookup"
    private const val REGION = "NL"

    // De diensten die we tonen. De namen moeten (deels) overeenkomen met wat
    // TMDB als provider_name teruggeeft.
    private val TRACKED_SERVICES = listOf(
        "Netflix",
        "Videoland",
        "Disney Plus",
        "Amazon Prime Video",
        "Apple TV",
        "HBO Max",
        "SkyShowtime"
    )

    data class MovieResult(
        val title: String,
        val year: String?,
        val isSeries: Boolean,
        val availableOn: List<String>,
        val tmdbUrl: String?
    )

    sealed class LookupOutcome {
        data class Success(val result: MovieResult) : LookupOutcome()
        data class NotFound(val query: String) : LookupOutcome()
        data class Error(val message: String) : LookupOutcome()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun search(query: String, callback: (LookupOutcome) -> Unit) {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) {
            callback(
                LookupOutcome.Error(
                    "Geen TMDB API key ingesteld. Zet je key in gradle.properties (TMDB_API_KEY)."
                )
            )
            return
        }

        Thread {
            try {
                val hit = searchTitle(query, apiKey)
                if (hit == null) {
                    mainHandler.post { callback(LookupOutcome.NotFound(query)) }
                    return@Thread
                }

                val (id, title, dateStr, isSeries) = hit
                val services = fetchWatchProviders(id, isSeries, apiKey)
                val year = dateStr?.takeIf { it.length >= 4 }?.substring(0, 4)
                val urlType = if (isSeries) "tv" else "movie"
                val result = MovieResult(
                    title = title,
                    year = year,
                    isSeries = isSeries,
                    availableOn = services,
                    tmdbUrl = "https://www.themoviedb.org/$urlType/$id"
                )
                mainHandler.post { callback(LookupOutcome.Success(result)) }
            } catch (e: Exception) {
                Log.e(TAG, "Opzoeken mislukt", e)
                mainHandler.post {
                    callback(LookupOutcome.Error("Opzoeken mislukt: ${e.message ?: "onbekende fout"}"))
                }
            }
        }.start()
    }

    private data class TitleHit(val id: Int, val title: String, val date: String?, val isSeries: Boolean, val popularity: Double)

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Zoekt in zowel films als series tegelijk (TMDB's "multi search").
     * TMDB's eigen volgorde is soms té los (matcht op willekeurige woorden),
     * en pure populariteit alleen kan een compleet onverwante, toevallig
     * gelijkluidende titel naar boven halen. Daarom: eerst prioriteren op
     * hoe goed de titel tekstueel overeenkomt met de zoekopdracht, en pas
     * daarbinnen op populariteit (bekendheid) kiezen.
     */
    private fun searchTitle(query: String, apiKey: String): TitleHit? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL(
            "https://api.themoviedb.org/3/search/multi" +
                "?api_key=$apiKey&language=nl-NL&query=$encoded"
        )
        val body = get(url)
        val results = JSONObject(body).optJSONArray("results") ?: return null

        val hits = mutableListOf<TitleHit>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val popularity = item.optDouble("popularity", 0.0)
            when (item.optString("media_type", "")) {
                "movie" -> hits.add(
                    TitleHit(
                        id = item.getInt("id"),
                        title = item.optString("title", query),
                        date = item.optString("release_date", null),
                        isSeries = false,
                        popularity = popularity
                    )
                )
                "tv" -> hits.add(
                    TitleHit(
                        id = item.getInt("id"),
                        title = item.optString("name", query),
                        date = item.optString("first_air_date", null),
                        isSeries = true,
                        popularity = popularity
                    )
                )
                // "person" en andere types slaan we over, daar zoeken we niet naar.
            }
        }
        if (hits.isEmpty()) return null

        val normalizedQuery = normalize(query)

        // Tier 0: titel komt (genormaliseerd) exact overeen met de zoekopdracht.
        val exact = hits.filter { normalize(it.title) == normalizedQuery }
        if (exact.isNotEmpty()) return exact.maxByOrNull { it.popularity }

        // Tier 1: titel en zoekopdracht overlappen tekstueel (bevatten elkaar).
        val partial = hits.filter {
            val t = normalize(it.title)
            t.contains(normalizedQuery) || normalizedQuery.contains(t)
        }
        if (partial.isNotEmpty()) return partial.maxByOrNull { it.popularity }

        // Tier 2: geen enkele tekstuele overlap — val terug op TMDB's eigen
        // relevantie-volgorde (het eerste resultaat) in plaats van populariteit,
        // want populariteit zonder tekstuele match trekt vaak iets compleet anders aan.
        return hits.first()
    }

    private fun fetchWatchProviders(id: Int, isSeries: Boolean, apiKey: String): List<String> {
        val urlType = if (isSeries) "tv" else "movie"
        val url = URL("https://api.themoviedb.org/3/$urlType/$id/watch/providers?api_key=$apiKey")
        val body = get(url)
        val results = JSONObject(body).optJSONObject("results") ?: return emptyList()
        val nl = results.optJSONObject(REGION) ?: return emptyList()

        val foundNames = linkedSetOf<String>()
        for (category in listOf("flatrate", "free", "ads", "rent", "buy")) {
            val array = nl.optJSONArray(category) ?: continue
            for (i in 0 until array.length()) {
                val providerName = array.getJSONObject(i).optString("provider_name", "")
                TRACKED_SERVICES.firstOrNull { tracked ->
                    providerName.contains(tracked, ignoreCase = true) ||
                        tracked.contains(providerName, ignoreCase = true)
                }?.let { foundNames.add(it) }
            }
        }
        return foundNames.toList()
    }

    private fun get(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
