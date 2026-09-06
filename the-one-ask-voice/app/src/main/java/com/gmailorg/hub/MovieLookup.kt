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

    data class MovieResult(
        val title: String,
        val year: String?,
        val isSeries: Boolean,
        val availableOn: List<String>,
        val rentOrBuyOn: List<String>,
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
                val (streaming, rentBuy) = fetchWatchProviders(id, isSeries, apiKey)
                val year = dateStr?.takeIf { it.length >= 4 }?.substring(0, 4)
                val urlType = if (isSeries) "tv" else "movie"
                val result = MovieResult(
                    title = title,
                    year = year,
                    isSeries = isSeries,
                    availableOn = streaming,
                    rentOrBuyOn = rentBuy,
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

    private data class TitleHit(val id: Int, val title: String, val date: String?, val isSeries: Boolean, val voteCount: Int)

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Zoekt in zowel films als series tegelijk (TMDB's "multi search").
     * We filteren op tekstuele gelijkenis met de zoekopdracht, en kiezen
     * daarbinnen (bij meerdere exacte matches, zoals een bekende serie en een
     * obscure gelijknamige film) degene met de meeste stemmen (vote_count) —
     * een stabielere maatstaf voor bekendheid dan TMDB's eigen volgorde of
     * de kortetermijn-"populariteit", die soms juist de verkeerde titel naar
     * boven haalt.
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
            val voteCount = item.optInt("vote_count", 0)
            when (item.optString("media_type", "")) {
                "movie" -> hits.add(
                    TitleHit(
                        id = item.getInt("id"),
                        title = item.optString("title", query),
                        date = item.optString("release_date", null),
                        isSeries = false,
                        voteCount = voteCount
                    )
                )
                "tv" -> hits.add(
                    TitleHit(
                        id = item.getInt("id"),
                        title = item.optString("name", query),
                        date = item.optString("first_air_date", null),
                        isSeries = true,
                        voteCount = voteCount
                    )
                )
                // "person" en andere types slaan we over, daar zoeken we niet naar.
            }
        }
        if (hits.isEmpty()) return null

        val normalizedQuery = normalize(query)

        // Tier 0: titel komt (genormaliseerd) exact overeen — bij meerdere
        // exacte matches wint de bekendste (meeste stemmen).
        val exact = hits.filter { normalize(it.title) == normalizedQuery }
        if (exact.isNotEmpty()) return exact.maxByOrNull { it.voteCount }

        // Tier 1: titel en zoekopdracht overlappen tekstueel (bevatten elkaar).
        val partial = hits.filter {
            val t = normalize(it.title)
            t.contains(normalizedQuery) || normalizedQuery.contains(t)
        }
        if (partial.isNotEmpty()) return partial.maxByOrNull { it.voteCount }

        // Tier 2: geen enkele tekstuele overlap — val terug op TMDB's eerste
        // (meest relevante) resultaat.
        return hits.first()
    }

    /** @return Pair(diensten met abonnement/gratis, diensten om te huren/kopen) — alles wat TMDB voor NL teruggeeft. */
    private fun fetchWatchProviders(id: Int, isSeries: Boolean, apiKey: String): Pair<List<String>, List<String>> {
        val urlType = if (isSeries) "tv" else "movie"
        val url = URL("https://api.themoviedb.org/3/$urlType/$id/watch/providers?api_key=$apiKey")
        val body = get(url)
        val results = JSONObject(body).optJSONObject("results") ?: return Pair(emptyList(), emptyList())
        val nl = results.optJSONObject(REGION) ?: return Pair(emptyList(), emptyList())

        fun namesIn(category: String): List<String> {
            val array = nl.optJSONArray(category) ?: return emptyList()
            val names = linkedSetOf<String>()
            for (i in 0 until array.length()) {
                names.add(array.getJSONObject(i).optString("provider_name", ""))
            }
            return names.filter { it.isNotBlank() }.toList()
        }

        val streaming = linkedSetOf<String>()
        streaming.addAll(namesIn("flatrate"))
        streaming.addAll(namesIn("free"))
        streaming.addAll(namesIn("ads"))

        val rentBuy = linkedSetOf<String>()
        rentBuy.addAll(namesIn("rent"))
        rentBuy.addAll(namesIn("buy"))
        // Als iets al bij abonnement/gratis staat, niet dubbel bij huren/kopen tonen.
        rentBuy.removeAll(streaming)

        return Pair(streaming.toList(), rentBuy.toList())
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
