package com.gmailorg.hub

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ShoppingItem(
    val id: String,
    val text: String,
    var done: Boolean = false
)

/**
 * Bewaart de boodschappenlijst van de Huishouden-tegel. Nieuwe items komen
 * bovenaan te staan; afgevinkte items blijven zichtbaar (doorgestreept) tot
 * ze handmatig verwijderd worden, zodat je kunt zien wat je al in huis hebt.
 */
object ShoppingListStore {

    private const val PREFS = "household_shopping_list"
    private const val KEY_ITEMS = "items"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val items = mutableListOf<ShoppingItem>()

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    fun getAll(): List<ShoppingItem> = items.toList()

    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        items.add(0, ShoppingItem(id = UUID.randomUUID().toString(), text = trimmed))
        persist()
    }

    fun toggleDone(id: String) {
        items.find { it.id == id }?.let { it.done = !it.done }
        persist()
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        persist()
    }

    fun clearDone() {
        items.removeAll { it.done }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("id", item.id)
            o.put("text", item.text)
            o.put("done", item.done)
            arr.put(o)
        }
        prefs?.edit()?.putString(KEY_ITEMS, arr.toString())?.apply()
        appContext?.let { ShoppingListWidgetProvider.updateAllWidgets(it) }
    }

    private fun load() {
        val raw = prefs?.getString(KEY_ITEMS, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    ShoppingItem(
                        id = o.getString("id"),
                        text = o.getString("text"),
                        done = o.optBoolean("done", false)
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupte opslag negeren.
        }
    }
}
