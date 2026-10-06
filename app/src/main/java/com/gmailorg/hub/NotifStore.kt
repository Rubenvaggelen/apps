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
    private const val KEY_DISMISSED_ACCESS = "dismissed_access_keys"
    private val dismissedAccess = mutableSetOf<String>()

    private val items = mutableListOf<NotifItem>()
    private val listeners = mutableListOf<() -> Unit>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dismissedAccess.addAll(prefs?.getStringSet(KEY_DISMISSED_ACCESS, emptySet()).orEmpty())
        load()
    }

    @Synchronized
    fun getAll(): List<NotifItem> = items.sortedByDescending { it.postTime }

    @Synchronized
    fun addOrUpdate(item: NotifItem) {
        if (item.actionType == "access_request" && item.key in dismissedAccess) return
        items.removeAll { it.key == item.key }
        items.add(item)
        if (items.size > MAX_ITEMS) {
            var removeCount = items.size - MAX_ITEMS
            val removable = items
                .filter { !it.persistent }
                .sortedBy { it.postTime }
            removable.take(removeCount).forEach {
                if (items.remove(it)) removeCount--
            }
            // Persistent items are unresolved actions and are never evicted.
            // In the extremely unlikely case there are > MAX_ITEMS persistent
            // requests, keep them all until they are handled.
        }
        persist()
        notifyListeners()
    }

    @Synchronized
    fun removeByKey(key: String, force: Boolean = false) {
        val dismissed = items.filter { it.key == key && it.actionType == "access_request" }
        dismissedAccess.addAll(dismissed.map { it.key })
        val changed = items.removeAll { it.key == key && (force || !it.persistent || it.actionType == "access_request") }
        if (changed) {
            persist()
            notifyListeners()
        }
    }

    @Synchronized
    fun removeWhere(
        includePersistent: Boolean = false,
        predicate: (NotifItem) -> Boolean
    ) {
        val changed = items.removeAll { item ->
            (includePersistent || !item.persistent) && predicate(item)
        }
        if (changed) {
            persist()
            notifyListeners()
        }
    }

    @Synchronized
    fun clearAll() {
        dismissedAccess.addAll(items.filter { it.actionType == "access_request" }.map { it.key })
        val changed = items.removeAll { !it.persistent || it.actionType == "access_request" }
        if (changed) {
            persist()
            notifyListeners()
        }
    }

    @Synchronized
    fun subscribe(listener: () -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
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
            o.put("persistent", n.persistent)
            o.put("actionType", n.actionType)
            o.put("actionValue", n.actionValue)
            arr.put(o)
        }
        prefs?.edit()?.putString(KEY_ITEMS, arr.toString())?.putStringSet(KEY_DISMISSED_ACCESS, dismissedAccess.toSet())?.apply()
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
                        hasReplyAction = o.optBoolean("hasReplyAction", false),
                        persistent = o.optBoolean("persistent", false),
                        actionType = o.optString("actionType", ""),
                        actionValue = o.optString("actionValue", "")
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupte opslag negeren, gewoon leeg beginnen.
        }
    }
}
