package com.gmailorg.hub

import org.json.JSONObject

/** Reject partial Overpass responses, so a timeout never means "no shops". */
object SupermarketLookupParser {
    fun parse(body: String): List<Pair<Double, Double>> {
        val json = JSONObject(body)
        require(json.optString("remark").isBlank()) { "Kaartserver heeft de zoekopdracht niet voltooid" }
        val elements = json.getJSONArray("elements")
        return (0 until elements.length()).mapNotNull { i ->
            val element = elements.getJSONObject(i)
            val point = element.optJSONObject("center") ?: element
            val lat = point.optDouble("lat", Double.NaN)
            val lon = point.optDouble("lon", Double.NaN)
            if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
                lat to lon else null
        }.distinct()
    }
}

