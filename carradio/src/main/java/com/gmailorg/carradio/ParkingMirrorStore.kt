package com.gmailorg.carradio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CarParkingAddress(val id: String, val address: String)

object ParkingMirrorStore {
    private const val PREFS = "car_parking_mirror"
    private const val KEY_ITEMS = "items"
    private const val KEY_TIMER = "timer"
    private val staging = mutableListOf<CarParkingAddress>()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beginSync() = synchronized(staging) { staging.clear() }
    fun stage(item: CarParkingAddress) = synchronized(staging) { staging.add(item) }
    fun finishSync(context: Context) {
        val arr = JSONArray()
        synchronized(staging) { staging.forEach { arr.put(JSONObject().apply { put("id", it.id); put("address", it.address) }) } }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
    fun items(context: Context): List<CarParkingAddress> {
        val raw = prefs(context).getString(KEY_ITEMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(CarParkingAddress(o.getString("id"), o.getString("address")))
                }
            }
        } catch (_: Exception) { emptyList() }
    }
    fun setTimer(context: Context, millis: Long?) {
        val e = prefs(context).edit()
        if (millis != null && millis > 0) e.putLong(KEY_TIMER, millis) else e.remove(KEY_TIMER)
        e.apply()
    }
    fun timer(context: Context): Long? {
        val v = prefs(context).getLong(KEY_TIMER, -1L)
        return v.takeIf { it > 0 }
    }
}
