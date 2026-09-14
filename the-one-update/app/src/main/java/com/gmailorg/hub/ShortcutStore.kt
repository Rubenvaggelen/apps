package com.gmailorg.hub

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bewaart welke apps de gebruiker zelf aan het startscherm heeft toegevoegd
 * (bijv. Google Home, LSC Smart Connect). De twee vaste tegels (Meldingen,
 * Mail & Kalender) staan hier niet in — die worden altijd getoond.
 */
object ShortcutStore {

    private const val PREFS = "home_shortcuts"
    private const val KEY_ITEMS = "shortcuts"

    private var prefs: SharedPreferences? = null
    private val shortcuts = mutableListOf<HomeTile>()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    fun getAll(): List<HomeTile> = shortcuts.toList()

    fun add(tile: HomeTile) {
        if (shortcuts.any { it.packageName == tile.packageName }) return
        shortcuts.add(tile)
        persist()
    }

    fun remove(packageName: String) {
        shortcuts.removeAll { it.packageName == packageName }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        shortcuts.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("label", t.label)
            o.put("packageName", t.packageName)
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
                shortcuts.add(
                    HomeTile(
                        id = o.getString("id"),
                        type = TileType.APP,
                        label = o.getString("label"),
                        packageName = o.optString("packageName", "").ifEmpty { null }
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupte opslag negeren.
        }
    }
}
