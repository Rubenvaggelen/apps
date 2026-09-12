package com.gmailorg.hub

import android.content.Context
import java.util.Locale

/**
 * Bewaart welke WhatsApp-contacten/groepen naar de autoradio mogen.
 * De telefoon-UI blijft ongewijzigd; deze voorkeuren worden vanaf de radio beheerd.
 */
object WhatsAppCarFilterStore {
    private const val PREFS = "whatsapp_car_filter"
    private const val KEY_FILTER_ENABLED = "filter_enabled"
    private const val KEY_KNOWN = "known_contacts"
    private const val KEY_ALLOWED = "allowed_contacts"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clean(name: String): String = name.trim()

    fun isFilterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILTER_ENABLED, true)

    fun setFilterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FILTER_ENABLED, enabled).apply()
    }

    fun registerSeen(context: Context, name: String) {
        val value = clean(name)
        if (value.isBlank()) return
        val set = knownContacts(context).toMutableSet()
        if (set.none { it.equals(value, ignoreCase = true) }) {
            set.add(value)
            prefs(context).edit().putStringSet(KEY_KNOWN, set).apply()
        }
    }

    fun knownContacts(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_KNOWN, emptySet())?.toSet().orEmpty()

    fun allowedContacts(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()

    fun setAllowed(context: Context, name: String, allowed: Boolean) {
        val value = clean(name)
        if (value.isBlank()) return
        registerSeen(context, value)
        val set = allowedContacts(context).toMutableSet()
        set.removeAll { it.equals(value, ignoreCase = true) }
        if (allowed) set.add(value)
        prefs(context).edit().putStringSet(KEY_ALLOWED, set).apply()
    }

    fun isAllowed(context: Context, name: String): Boolean {
        if (!isFilterEnabled(context)) return true
        val normalized = clean(name).lowercase(Locale.ROOT)
        return allowedContacts(context).any { it.trim().lowercase(Locale.ROOT) == normalized }
    }
}
