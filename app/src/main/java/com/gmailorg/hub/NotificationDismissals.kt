package com.gmailorg.hub

import java.security.MessageDigest

/** Retain local dismissals across replays, without hiding new messages or sessions. */
class NotificationDismissals(saved: Map<String, String> = emptyMap()) {
    private val entries = LinkedHashMap(saved)
    private val limit = 1000

    fun remember(item: NotifItem) {
        entries.remove(item.key)
        entries[item.key] = fingerprint(item)
        while (entries.size > limit) entries.remove(entries.keys.first())
    }

    fun shouldSuppress(item: NotifItem): Boolean {
        val dismissed = entries[item.key] ?: return false
        val incoming = fingerprint(item)
        if (dismissed == incoming ||
            (item.ongoing && dismissed.substringBefore(':') == incoming.substringBefore(':'))) return true
        entries.remove(item.key)
        return false
    }

    fun sourceRemoved(key: String) { entries.remove(key) }
    fun snapshot(): Map<String, String> = LinkedHashMap(entries)

    private fun fingerprint(item: NotifItem): String {
        // Casting/ongoing notifications refresh timestamps even when content is unchanged.
        // Normal messages may repeat the same body, so their actual posting time matters.
        val fields = listOf(item.packageName, item.title, item.text)
        val encoded = fields.joinToString("") { "${it.length}:$it" }
        val content = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return content + ":" + item.postTime
    }
}
