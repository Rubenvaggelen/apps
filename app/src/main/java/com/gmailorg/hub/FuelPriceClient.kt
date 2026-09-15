package com.gmailorg.hub

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object FuelPriceClient {
    private const val BASE_URL = "https://api.anwb.nl/routing/points-of-interest/v3/all"
    const val RADIUS_KM = 5.0

    enum class Fuel(val apiName: String, val label: String) {
        E10("EURO95", "Euro 95 / E10"),
        E5("EURO98", "Super 98 / E5"),
        DIESEL("DIESEL", "Diesel")
    }

    data class Station(
        val id: String,
        val name: String,
        val address: String,
        val city: String,
        val lat: Double,
        val lon: Double,
        val price: Double,
        val currency: String,
        val distanceKm: Double
    )

    fun fetchNearby(lat: Double, lon: Double, fuel: Fuel): List<Station> {
        val box = boundingBox(lat, lon, RADIUS_KM)
        val bbox = listOf(box.south, box.west, box.north, box.east)
            .joinToString(",") { String.format(Locale.US, "%.6f", it) }
        val url = URL(
            "$BASE_URL?type-filter=FUEL_STATION&bounding-box-filter=" +
                URLEncoder.encode(bbox, StandardCharsets.UTF_8.name())
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "The-One-Android/1.0")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Prijsservice gaf HTTP $code")
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            val values = root.optJSONArray("value") ?: return emptyList()
            val stations = mutableListOf<Station>()

            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i) ?: continue
                val coords = item.optJSONObject("coordinates") ?: continue
                val stationLat = coords.optDouble("latitude", Double.NaN)
                val stationLon = coords.optDouble("longitude", Double.NaN)
                if (stationLat.isNaN() || stationLon.isNaN()) continue

                val addressObject = item.optJSONObject("address")
                val country = addressObject?.optString("iso3CountryCode").orEmpty()
                if (country.isNotBlank() && country != "NLD") continue

                val distance = haversineKm(lat, lon, stationLat, stationLon)
                if (distance > RADIUS_KM) continue

                val prices = item.optJSONArray("prices") ?: continue
                var price: Double? = null
                var currency = "EUR"
                for (p in 0 until prices.length()) {
                    val po = prices.optJSONObject(p) ?: continue
                    if (po.optString("fuelType").equals(fuel.apiName, ignoreCase = true)) {
                        val v = po.optDouble("value", Double.NaN)
                        if (!v.isNaN() && v > 0) {
                            price = v
                            currency = po.optString("currency", "EUR").ifBlank { "EUR" }
                            break
                        }
                    }
                }
                val selectedPrice = price ?: continue
                stations += Station(
                    id = item.optString("id", "$stationLat,$stationLon"),
                    name = item.optString("title", "Tankstation").ifBlank { "Tankstation" },
                    address = addressObject?.optString("streetAddress").orEmpty(),
                    city = addressObject?.optString("city").orEmpty(),
                    lat = stationLat,
                    lon = stationLon,
                    price = selectedPrice,
                    currency = currency,
                    distanceKm = distance
                )
            }
            return stations.sortedWith(compareBy<Station> { it.price }.thenBy { it.distanceKm })
        } finally {
            connection.disconnect()
        }
    }

    private data class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    private fun boundingBox(lat: Double, lon: Double, radiusKm: Double): Box {
        val latDelta = radiusKm / 111.0
        val lonScale = 111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val lonDelta = radiusKm / lonScale
        return Box(lat - latDelta, lon - lonDelta, lat + latDelta, lon + lonDelta)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusKm * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
