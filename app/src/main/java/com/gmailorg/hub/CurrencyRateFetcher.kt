package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Haalt EUR-basisrates op voor EUR, SRD en USD via een sleutelloze koers-API. */
object CurrencyRateFetcher {
    private const val TAG = "CurrencyRateFetcher"
    private val mainHandler = Handler(Looper.getMainLooper())

    data class Rates(val eurToSrd: Double, val eurToUsd: Double) {
        fun perEur(currency: String): Double? = when (currency.uppercase()) {
            "EUR" -> 1.0
            "SRD" -> eurToSrd
            "USD" -> eurToUsd
            else -> null
        }
    }

    sealed class RateOutcome {
        data class Success(val rates: Rates) : RateOutcome()
        data class Error(val message: String) : RateOutcome()
    }

    fun fetchRate(callback: (RateOutcome) -> Unit) {
        Thread {
            try {
                val connection = URL("https://open.er-api.com/v6/latest/EUR").openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.requestMethod = "GET"
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val json = JSONObject(body)
                val rates = json.optJSONObject("rates")
                val srd = rates?.optDouble("SRD", Double.NaN) ?: Double.NaN
                val usd = rates?.optDouble("USD", Double.NaN) ?: Double.NaN
                if (srd.isNaN() || usd.isNaN()) {
                    mainHandler.post { callback(RateOutcome.Error("EUR/SRD/USD-koersen niet gevonden in het antwoord.")) }
                } else {
                    mainHandler.post { callback(RateOutcome.Success(Rates(srd, usd))) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Koers ophalen mislukt", e)
                mainHandler.post { callback(RateOutcome.Error("Ophalen mislukt: ${e.message ?: "onbekende fout"}")) }
            }
        }.start()
    }
}
