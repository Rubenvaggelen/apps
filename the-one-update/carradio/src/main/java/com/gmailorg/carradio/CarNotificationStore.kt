package com.gmailorg.carradio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Alleen de meldingen die in de tegel Meldingen zichtbaar horen te zijn. */
object CarNotificationStore {
    data class Item(val contact: String, val text: String, val time: Long)

    private const val PREFS = "the_one_car_notifications"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 120

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(context: Context, contact: String, text: String, time: Long) {
        if (contact.isBlank() || text.isBlank()) return
        val list = all(context).toMutableList()
        val duplicate = list.takeLast(12).any {
            it.contact.equals(contact, true) && it.text == text && kotlin.math.abs(it.time - time) < 2500L
        }
        if (duplicate) return
        list += Item(contact.trim(), text.trim(), time)
        while (list.size > MAX_ITEMS) list.removeAt(0)
        save(context, list)
    }

    @Synchronized
    fun recent(context: Context, limit: Int = 80): List<Item> =
        all(context).sortedByDescending { it.time }.take(limit.coerceAtLeast(1))

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ITEMS).apply()
    }

    private fun all(context: Context): List<Item> {
        val raw = prefs(context).getString(KEY_ITEMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val contact = o.optString("contact")
                    val text = o.optString("text")
                    if (contact.isBlank() || text.isBlank()) continue
                    add(Item(contact, text, o.optLong("time", 0L)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("contact", item.contact)
                put("text", item.text)
                put("time", item.time)
            })
        }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
