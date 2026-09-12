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
        val read: Boolean,
        val voiceNote: Boolean = false,
        val mediaPath: String? = null
    )

    data class Summary(val contact: String, val latestText: String, val latestTime: Long, val unread: Int)

    private const val PREFS = "the_one_car_conversations"
    private const val KEY_MESSAGES = "messages"
    private const val MAX_MESSAGES = 300
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun looksVoice(text: String): Boolean {
        val l = text.lowercase()
        return l.contains("spraakbericht") || l.contains("voice message") || l.contains("audio message") || l.startsWith("🎤")
    }

    @Synchronized fun addIncoming(context: Context, contact: String, text: String, time: Long) = add(context, contact, text, time, false, false, looksVoice(text), null)
    @Synchronized fun addOutgoing(context: Context, contact: String, text: String, time: Long) = add(context, contact, text, time, true, true, false, null)

    private fun add(context: Context, contact: String, text: String, time: Long, mine: Boolean, read: Boolean, voice: Boolean, mediaPath: String?) {
        if (contact.isBlank() || text.isBlank()) return
        val list = readAll(context).toMutableList()
        val duplicate = list.takeLast(12).any { it.contact == contact && it.text == text && it.mine == mine && kotlin.math.abs(it.time - time) < 2500L }
        if (duplicate) return
        list.add(ChatMessage(contact.trim(), text.trim(), time, mine, read, voice, mediaPath))
        while (list.size > MAX_MESSAGES) list.removeAt(0)
        save(context, list)
    }

    @Synchronized fun attachLatestVoiceMedia(context: Context, contact: String, path: String) {
        val list = readAll(context).toMutableList()
        val idx = list.indexOfLast { !it.mine && it.contact.equals(contact, true) && it.voiceNote }
        if (idx >= 0) list[idx] = list[idx].copy(mediaPath = path)
        else list.add(ChatMessage(contact, "🎤 Spraakbericht", System.currentTimeMillis(), false, false, true, path))
        save(context, list)
    }

    @Synchronized fun messagesFor(context: Context, contact: String): List<ChatMessage> = readAll(context).filter { it.contact.equals(contact, true) }
    @Synchronized fun recentIncoming(context: Context, limit: Int = 80): List<ChatMessage> = readAll(context).asSequence().filter { !it.mine }.sortedByDescending { it.time }.take(limit.coerceAtLeast(1)).toList()
    @Synchronized fun summaries(context: Context): List<Summary> = readAll(context).groupBy { it.contact }.mapNotNull { (contact, messages) ->
        val latest = messages.maxByOrNull { it.time } ?: return@mapNotNull null
        Summary(contact, latest.text, latest.time, messages.count { !it.mine && !it.read })
    }.sortedByDescending { it.latestTime }

    @Synchronized fun markRead(context: Context, contact: String) {
        save(context, readAll(context).map { if (it.contact.equals(contact, true) && !it.mine) it.copy(read = true) else it })
    }
    @Synchronized fun clear(context: Context) { prefs(context).edit().remove(KEY_MESSAGES).apply() }

    private fun readAll(context: Context): List<ChatMessage> {
        val raw = prefs(context).getString(KEY_MESSAGES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val contact = o.optString("contact"); val text = o.optString("text")
                    if (contact.isBlank() || text.isBlank()) continue
                    add(ChatMessage(contact, text, o.optLong("time", 0L), o.optBoolean("mine", false), o.optBoolean("read", false), o.optBoolean("voiceNote", looksVoice(text)), o.optString("mediaPath").takeIf { it.isNotBlank() }))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(context: Context, messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.forEach { m -> arr.put(JSONObject().apply {
            put("contact", m.contact); put("text", m.text); put("time", m.time); put("mine", m.mine); put("read", m.read); put("voiceNote", m.voiceNote)
            if (!m.mediaPath.isNullOrBlank()) put("mediaPath", m.mediaPath)
        }) }
        prefs(context).edit().putString(KEY_MESSAGES, arr.toString()).apply()
    }
}
