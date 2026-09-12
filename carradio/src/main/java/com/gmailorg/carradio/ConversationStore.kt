package com.gmailorg.carradio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ConversationStore {
    data class ChatMessage(
        val contact: String,
        val text: String,
        val time: Long,
        val mine: Boolean,
        val read: Boolean
    )

    data class Summary(
        val contact: String,
        val latestText: String,
        val latestTime: Long,
        val unread: Int
    )

    private const val PREFS = "the_one_car_conversations"
    private const val KEY_MESSAGES = "messages"
    private const val MAX_MESSAGES = 300

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun addIncoming(context: Context, contact: String, text: String, time: Long) {
        add(context, contact, text, time, mine = false, read = false)
    }

    @Synchronized
    fun addOutgoing(context: Context, contact: String, text: String, time: Long) {
        add(context, contact, text, time, mine = true, read = true)
    }

    private fun add(context: Context, contact: String, text: String, time: Long, mine: Boolean, read: Boolean) {
        if (contact.isBlank() || text.isBlank()) return
        val list = readAll(context).toMutableList()
        // Voorkom dubbele WhatsApp-notificaties bij reconnect/activeNotifications.
        val duplicate = list.takeLast(12).any {
            it.contact == contact && it.text == text && it.mine == mine && kotlin.math.abs(it.time - time) < 2500L
        }
        if (duplicate) return
        list.add(ChatMessage(contact.trim(), text.trim(), time, mine, read))
        while (list.size > MAX_MESSAGES) list.removeAt(0)
        save(context, list)
    }

    @Synchronized
    fun messagesFor(context: Context, contact: String): List<ChatMessage> =
        readAll(context).filter { it.contact.equals(contact, ignoreCase = true) }

    @Synchronized
    fun recentIncoming(context: Context, limit: Int = 80): List<ChatMessage> =
        readAll(context)
            .asSequence()
            .filter { !it.mine }
            .sortedByDescending { it.time }
            .take(limit.coerceAtLeast(1))
            .toList()

    @Synchronized
    fun summaries(context: Context): List<Summary> {
        val grouped = readAll(context).groupBy { it.contact }
        return grouped.mapNotNull { (contact, messages) ->
            val latest = messages.maxByOrNull { it.time } ?: return@mapNotNull null
            Summary(
                contact = contact,
                latestText = latest.text,
                latestTime = latest.time,
                unread = messages.count { !it.mine && !it.read }
            )
        }.sortedByDescending { it.latestTime }
    }

    @Synchronized
    fun markRead(context: Context, contact: String) {
        val updated = readAll(context).map {
            if (it.contact.equals(contact, ignoreCase = true) && !it.mine) it.copy(read = true) else it
        }
        save(context, updated)
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_MESSAGES).apply()
    }

    private fun readAll(context: Context): List<ChatMessage> {
        val raw = prefs(context).getString(KEY_MESSAGES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val contact = o.optString("contact")
                    val text = o.optString("text")
                    if (contact.isBlank() || text.isBlank()) continue
                    add(
                        ChatMessage(
                            contact = contact,
                            text = text,
                            time = o.optLong("time", 0L),
                            mine = o.optBoolean("mine", false),
                            read = o.optBoolean("read", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("contact", m.contact)
                put("text", m.text)
                put("time", m.time)
                put("mine", m.mine)
                put("read", m.read)
            })
        }
        prefs(context).edit().putString(KEY_MESSAGES, arr.toString()).apply()
    }
}
