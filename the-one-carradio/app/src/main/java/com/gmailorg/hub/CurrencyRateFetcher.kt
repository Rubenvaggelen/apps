package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Haalt de actuele EUR -> SRD-wisselkoers op via een gratis, sleutelloze API.
 */
object CurrencyRateFetcher {

    private const val TAG = "CurrencyRateFetcher"
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed class RateOutcome {
        data class Success(val eurToSrd: Double) : RateOutcome()
        data class Error(val message: String) : RateOutcome()
    }

    fun fetchRate(callback: (RateOutcome) -> Unit) {
        Thread {
            try {
                val url = URL("https://open.er-api.com/v6/latest/EUR")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val json = JSONObject(body)
                val rates = json.optJSONObject("rates")
                val srdRate = rates?.optDouble("SRD", Double.NaN) ?: Double.NaN

                if (srdRate.isNaN()) {
                    mainHandler.post { callback(RateOutcome.Error("Koers niet gevonden in het antwoord.")) }
                } else {
                    mainHandler.post { callback(RateOutcome.Success(srdRate)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Koers ophalen mislukt", e)
                mainHandler.post { callback(RateOutcome.Error("Ophalen mislukt: ${e.message ?: "onbekende fout"}")) }
            }
        }.start()
    }
}
