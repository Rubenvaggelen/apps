package com.gmailorg.hub

import android.util.Log
import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Haalt items op uit een Firebase Realtime Database-"wachtrij" die IFTTT
 * daar zet zodra je "Hey Google, voeg ... toe aan boodschappen" zegt, en
 * plaatst ze automatisch op de boodschappenlijst. Verwijdert elk item
 * daarna uit de wachtrij zodat het niet dubbel wordt toegevoegd.
 *
 * Gebruikt bewust alleen platte HTTPS-aanroepen naar de Firebase REST API —
 * geen Firebase SDK of google-services.json nodig.
 */
object ShoppingQueueSync {

    private const val TAG = "ShoppingQueueSync"

    /** @return aantal nieuw toegevoegde items, of -1 bij een fout. */
    fun syncNow(context: Context): Int {
        val dbUrl = BuildConfig.FIREBASE_DB_URL.trimEnd('/')
        if (dbUrl.isBlank()) {
            Log.e(TAG, "Geen FIREBASE_DB_URL ingesteld in gradle.properties")
            return -1
        }
        ShoppingListStore.init(context.applicationContext)

        return try {
            val body = get("$dbUrl/queue.json")
            if (body.isBlank() || body == "null") return 0

            val json = JSONObject(body)
            var count = 0
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = json.optJSONObject(key)
                val text = entry?.optString("text", "")?.trim()
                if (!text.isNullOrEmpty()) {
                    ShoppingListStore.add(text)
                    count++
                }
                delete("$dbUrl/queue/$key.json")
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "Synchroniseren met de wachtrij mislukt", e)
            -1
        }
    }

    private fun get(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun delete(urlString: String) {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "DELETE"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        try {
            connection.inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Item uit wachtrij verwijderen mislukt", e)
        } finally {
            connection.disconnect()
        }
    }
}
