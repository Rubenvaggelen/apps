package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Zoekt een nummer/artiest op via de YouTube Data API v3 en geeft de eerste
 * 5 resultaten terug. Gebruikt voor de "Muziek zoeken"-sectie onder
 * Films, Series & Muziek.
 */
object MusicLookup {

    private const val TAG = "MusicLookup"
    private const val MAX_RESULTS = 5

    data class MusicResult(
        val videoId: String,
        val title: String,
        val channel: String
    )

    sealed class LookupOutcome {
        data class Success(val results: List<MusicResult>) : LookupOutcome()
        data class NotFound(val query: String) : LookupOutcome()
        data class Error(val message: String) : LookupOutcome()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun search(query: String, callback: (LookupOutcome) -> Unit) {
        val apiKey = BuildConfig.YOUTUBE_API_KEY
        if (apiKey.isBlank()) {
            callback(
                LookupOutcome.Error(
                    "Geen YouTube API key ingesteld. Zet je key in gradle.properties (YOUTUBE_API_KEY)."
                )
            )
            return
        }

        Thread {
            try {
                val results = searchVideos(query, apiKey)
                if (results.isEmpty()) {
                    mainHandler.post { callback(LookupOutcome.NotFound(query)) }
                } else {
                    mainHandler.post { callback(LookupOutcome.Success(results)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Muziek zoeken mislukt", e)
                mainHandler.post {
                    callback(LookupOutcome.Error("Zoeken mislukt: ${e.message ?: "onbekende fout"}"))
                }
            }
        }.start()
    }

    private fun searchVideos(query: String, apiKey: String): List<MusicResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL(
            "https://www.googleapis.com/youtube/v3/search" +
                "?part=snippet&type=video&maxResults=$MAX_RESULTS" +
                "&q=$encoded&key=$apiKey"
        )
        val body = get(url)
        val json = JSONObject(body)
        if (json.has("error")) {
            val message = json.optJSONObject("error")?.optString("message") ?: "Onbekende API-fout"
            throw RuntimeException(message)
        }
        val items = json.optJSONArray("items") ?: return emptyList()

        val results = mutableListOf<MusicResult>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val videoId = item.optJSONObject("id")?.optString("videoId") ?: continue
            val snippet = item.optJSONObject("snippet") ?: continue
            val title = htmlUnescape(snippet.optString("title", query))
            val channel = htmlUnescape(snippet.optString("channelTitle", ""))
            results.add(MusicResult(videoId = videoId, title = title, channel = channel))
        }
        return results
    }

    private fun htmlUnescape(text: String): String =
        text.replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun get(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
