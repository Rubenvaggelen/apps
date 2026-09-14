package com.gmailorg.carradio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CarShoppingItem(val id: String, val text: String, val done: Boolean)

object HouseholdMirrorStore {
    private const val PREFS = "car_household_mirror"
    private const val KEY_ITEMS = "items"
    private const val KEY_ALERT = "alert_enabled"

    private val staging = mutableListOf<CarShoppingItem>()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beginSync() = synchronized(staging) { staging.clear() }
    fun stage(item: CarShoppingItem) = synchronized(staging) { staging.add(item) }
    fun finishSync(context: Context) {
        val copy = synchronized(staging) { staging.toList() }
        val arr = JSONArray()
        copy.forEach { i -> arr.put(JSONObject().apply { put("id", i.id); put("text", i.text); put("done", i.done) }) }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun items(context: Context): List<CarShoppingItem> {
        val raw = prefs(context).getString(KEY_ITEMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(CarShoppingItem(o.getString("id"), o.getString("text"), o.optBoolean("done")))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun setAlertEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ALERT, enabled).apply()
    fun alertEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ALERT, false)
}
