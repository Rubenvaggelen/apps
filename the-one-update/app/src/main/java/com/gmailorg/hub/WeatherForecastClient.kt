package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

object WeatherForecastClient {
    private const val TAG = "WeatherForecast"
    private val mainHandler = Handler(Looper.getMainLooper())

    data class ForecastDay(
        val date: LocalDate,
        val maxTempC: Double,
        val precipitationProbability: Int,
        val maxWindKmh: Double,
        val weatherCode: Int
    )

    fun fetch(latitude: Double, longitude: Double, callback: (Result<List<ForecastDay>>) -> Unit) {
        Thread({
            try {
                val lat = String.format(Locale.US, "%.5f", latitude)
                val lon = String.format(Locale.US, "%.5f", longitude)
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&daily=weather_code,temperature_2m_max,precipitation_probability_max,wind_speed_10m_max" +
                        "&timezone=auto&forecast_days=7"
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    throw Exception("Weerservice gaf fout $status${if (body.isNotBlank()) ": $body" else ""}")
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val json = JSONObject(body)
                val daily = json.getJSONObject("daily")
                val times = daily.getJSONArray("time")
                val temperatures = daily.getJSONArray("temperature_2m_max")
                val rain = daily.getJSONArray("precipitation_probability_max")
                val wind = daily.getJSONArray("wind_speed_10m_max")
                val codes = daily.getJSONArray("weather_code")

                val result = ArrayList<ForecastDay>(times.length())
                for (i in 0 until times.length()) {
                    result += ForecastDay(
                        date = LocalDate.parse(times.getString(i)),
                        maxTempC = temperatures.optDouble(i, Double.NaN),
                        precipitationProbability = rain.optInt(i, 0),
                        maxWindKmh = wind.optDouble(i, 0.0),
                        weatherCode = codes.optInt(i, -1)
                    )
                }
                mainHandler.post { callback(Result.success(result)) }
            } catch (e: Exception) {
                Log.e(TAG, "Weersverwachting ophalen mislukt", e)
                mainHandler.post { callback(Result.failure(e)) }
            }
        }, "TheOne-Weather").start()
    }

    fun description(code: Int): String = when (code) {
        0 -> "helder"
        1 -> "overwegend helder"
        2 -> "gedeeltelijk bewolkt"
        3 -> "bewolkt"
        45, 48 -> "mist"
        51, 53, 55 -> "motregen"
        56, 57 -> "ijzelende motregen"
        61, 63, 65 -> "regen"
        66, 67 -> "ijzelende regen"
        71, 73, 75, 77 -> "sneeuw"
        80, 81, 82 -> "regenbuien"
        85, 86 -> "sneeuwbuien"
        95 -> "onweer"
        96, 99 -> "onweer met hagel"
        else -> "wisselend weer"
    }
}
