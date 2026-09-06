package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Zoekt een filmtitel op via TMDB en kijkt via het "watch/providers"-endpoint
 * op welke streamingdiensten (regio NL) de film te vinden is. Gebruikt voor
 * de "Film zoeken"-sectie onder Huishouden.
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
                val movie = searchMovie(query, apiKey)
                if (movie == null) {
                    mainHandler.post { callback(LookupOutcome.NotFound(query)) }
                    return@Thread
                }

                val (id, title, releaseDate) = movie
                val services = fetchWatchProviders(id, apiKey)
                val year = releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4)
                val result = MovieResult(
                    title = title,
                    year = year,
                    availableOn = services,
                    tmdbUrl = "https://www.themoviedb.org/movie/$id"
                )
                mainHandler.post { callback(LookupOutcome.Success(result)) }
            } catch (e: Exception) {
                Log.e(TAG, "Film opzoeken mislukt", e)
                mainHandler.post {
                    callback(LookupOutcome.Error("Opzoeken mislukt: ${e.message ?: "onbekende fout"}"))
                }
            }
        }.start()
    }

    /** @return Triple(tmdbId, titel, releaseDate) of null als niets gevonden is. */
    private fun searchMovie(query: String, apiKey: String): Triple<Int, String, String?>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL(
            "https://api.themoviedb.org/3/search/movie" +
                "?api_key=$apiKey&language=nl-NL&query=$encoded"
        )
        val body = get(url)
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val first = results.getJSONObject(0)
        val id = first.getInt("id")
        val title = first.optString("title", query)
        val releaseDate = first.optString("release_date", null)
        return Triple(id, title, releaseDate)
    }

    private fun fetchWatchProviders(movieId: Int, apiKey: String): List<String> {
        val url = URL("https://api.themoviedb.org/3/movie/$movieId/watch/providers?api_key=$apiKey")
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
