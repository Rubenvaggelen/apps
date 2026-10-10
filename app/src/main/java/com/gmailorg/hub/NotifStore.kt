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
    private const val KEY_DISMISSED = "dismissed_notification_versions"
    private var dismissals = NotificationDismissals()

    private val items = mutableListOf<NotifItem>()
    private val listeners = mutableListOf<() -> Unit>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = runCatching {
            val json = JSONObject(prefs?.getString(KEY_DISMISSED, "{}") ?: "{}")
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        dismissals = NotificationDismissals(saved)
        load()
    }

    @Synchronized
    fun getAll(): List<NotifItem> = items.sortedByDescending { it.postTime }

    @Synchronized
    fun addOrUpdate(item: NotifItem) {
        if (item.actionType.isBlank() && dismissals.shouldSuppress(item)) return
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
    fun removeByKey(key: String, force: Boolean = false, rememberDismissal: Boolean = true) {
        if (rememberDismissal) {
            items.filter { it.key == key && it.actionType.isBlank() && (force || !it.persistent) }
                .forEach { dismissals.remember(it) }
        } else {
            // Android confirms the source notification ended. A later new session is allowed.
            dismissals.sourceRemoved(key)
        }
        // No local operation may dismiss an unresolved access/license request.
        // Only an authoritative server status sync removes it using removeWhere().
        val changed = items.removeAll {
            it.key == key && it.actionType != "access_request" &&
                it.actionType != "license_request" && (force || !it.persistent)
        }
        if (changed) {
            persist()
            notifyListeners()
        } else if (!rememberDismissal) persist()
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
        items.filter { !it.persistent && it.actionType.isBlank() }.forEach { dismissals.remember(it) }
        val changed = items.removeAll {
            !it.persistent && it.actionType != "access_request" && it.actionType != "license_request"
        }
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
            o.put("ongoing", n.ongoing)
            arr.put(o)
        }
        val hidden = JSONObject()
        dismissals.snapshot().forEach { (key, signature) -> hidden.put(key, signature) }
        prefs?.edit()?.putString(KEY_ITEMS, arr.toString())
            ?.putString(KEY_DISMISSED, hidden.toString())
            ?.apply()
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
                        actionValue = o.optString("actionValue", ""),
                        ongoing = o.optBoolean("ongoing", false)
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupte opslag negeren, gewoon leeg beginnen.
        }
    }
}
