package com.gmailorg.hub

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ParkingAddress(
    val id: String,
    val address: String,
    var lat: Double? = null,
    var lon: Double? = null
)

/**
 * Bewaart de lijst met adressen waarvoor je een parkeer-herinnering wilt
 * ("Vergeet niet te betalen voor parkeren!") zodra je er aankomt. Elk adres
 * wordt eenmalig omgezet naar coördinaten (via Geocoder) en die coördinaten
 * worden hier gecached, zodat we niet steeds opnieuw hoeven te geocoderen.
 */
object ParkingAddressStore {

    private const val PREFS = "parking_addresses"
    private const val KEY_ITEMS = "items"

    private var prefs: SharedPreferences? = null
    private val items = mutableListOf<ParkingAddress>()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
        migrateSnackcidentIfNeeded()
    }

    // Eénmalige migratie: het adres dat eerder hardcoded stond (Snackcident)
    // komt nu als eerste, gewone item in de lijst te staan.
    private fun migrateSnackcidentIfNeeded() {
        val migrated = prefs?.getBoolean("migrated_snackcident", false) ?: true
        if (migrated) return
        if (items.none { it.address.equals("Vianenstraat 31, Amsterdam", ignoreCase = true) }) {
            add("Vianenstraat 31, Amsterdam")
        }
        prefs?.edit()?.putBoolean("migrated_snackcident", true)?.apply()
    }

    fun getAll(): List<ParkingAddress> = items.toList()

    fun get(id: String): ParkingAddress? = items.find { it.id == id }

    /** @return het nieuw aangemaakte adres, of null als de tekst leeg was. */
    fun add(address: String): ParkingAddress? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null
        val item = ParkingAddress(id = UUID.randomUUID().toString(), address = trimmed)
        items.add(0, item)
        persist()
        return item
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        persist()
    }

    fun updateCoords(id: String, lat: Double, lon: Double) {
        items.find { it.id == id }?.let {
            it.lat = lat
            it.lon = lon
        }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("id", item.id)
            o.put("address", item.address)
            if (item.lat != null) o.put("lat", item.lat)
            if (item.lon != null) o.put("lon", item.lon)
            arr.put(o)
        }
        prefs?.edit()?.putString(KEY_ITEMS, arr.toString())?.apply()
    }

    private fun load() {
        val raw = prefs?.getString(KEY_ITEMS, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    ParkingAddress(
                        id = o.getString("id"),
                        address = o.getString("address"),
                        lat = if (o.has("lat")) o.getDouble("lat") else null,
                        lon = if (o.has("lon")) o.getDouble("lon") else null
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupte opslag negeren.
        }
    }
}
