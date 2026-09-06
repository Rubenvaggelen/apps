package com.gmailorg.hub

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Vertaalt korte tekst via MyMemory — een gratis vertaal-API zonder key of
 * registratie. Gebruikt om Nederlandse zoektermen (bijv. bij Recepten) om te
 * zetten naar Engels vóór het zoeken in Engelstalige databases.
 */
object Translator {

    private const val TAG = "Translator"

    /** @return de vertaalde tekst, of de originele tekst als vertalen mislukt. */
    fun translate(text: String, from: String, to: String): String {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=$from|$to")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(body)
            val translated = json.getJSONObject("responseData").optString("translatedText", "")
            translated.ifBlank { text }
        } catch (e: Exception) {
            Log.e(TAG, "Vertalen mislukt, gebruik originele tekst", e)
            text
        }
    }
}
