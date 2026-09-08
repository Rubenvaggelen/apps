package com.gmailorg.hub

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple gedeelde opslag voor meldingen. Draait in hetzelfde proces als
 * zowel de NotificationListenerService als MainActivity, dus een singleton
 * volstaat. Wordt daarnaast naar SharedPreferences geschreven zodat de lijst
 * ook na een herstart van de app (niet van de service) behouden blijft.
 */
object NotifStore {

    private const val PREFS = "notif_hub_store"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 300

    private val items = mutableListOf<NotifItem>()
    private val listeners = mutableListOf<() -> Unit>()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    fun getAll(): List<NotifItem> = items.sortedByDescending { it.postTime }

    fun addOrUpdate(item: NotifItem) {
        items.removeAll { it.key == item.key }
        items.add(item)
        if (items.size > MAX_ITEMS) {
            val overflow = items.sortedBy { it.postTime }.take(items.size - MAX_ITEMS)
            items.removeAll(overflow)
        }
        persist()
        notifyListeners()
    }

    fun removeByKey(key: String) {
        items.removeAll { it.key == key }
        persist()
        notifyListeners()
    }

    fun clearAll() {
        items.clear()
        persist()
        notifyListeners()
    }

    fun subscribe(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it.invoke() }
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { n ->
            val o = JSONObject()
            o.put("key", n.key)
            o.put("packageName", n.packageName)
            o.put("appLabel", n.appLabel)
            o.put("title", n.title)
            o.put("text", n.text)
            o.put("postTime", n.postTime)
            o.put("hasReplyAction", n.hasReplyAction)
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
                    NotifItem(
                        key = o.getString("key"),
                        packageName = o.getString("packageName"),
                        appLabel = o.getString("appLabel"),
                        title = o.getString("title"),
                        text = o.getString("text"),
                        postTime = o.getLong("postTime"),
                        hasReplyAction = o.optBoolean("hasReplyAction", false)
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupte opslag negeren, gewoon leeg beginnen.
        }
    }
}
