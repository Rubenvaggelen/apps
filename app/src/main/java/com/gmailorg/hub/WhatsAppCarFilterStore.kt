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
    private const val KEY_REMOVED = "removed_contacts"
    private const val KEY_DEFAULTS_INITIALIZED = "defaults_initialized_v3"

    /** Standaard toegestane gesprekken voor The One Car. */
    val DEFAULT_ALLOWED_CONTACTS = linkedSetOf(
        "Beyonce aka FARQUAAD Van Aggelen", // Dochter
        "Susan Oehlers",                    // Ma
        "SST",                              // CA
        "Ruben Werk Tel"                    // Test
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clean(name: String): String = name.trim()

    private fun ensureDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_DEFAULTS_INITIALIZED, false)) return

        val known = p.getStringSet(KEY_KNOWN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val allowed = p.getStringSet(KEY_ALLOWED, emptySet())?.toMutableSet() ?: mutableSetOf()

        DEFAULT_ALLOWED_CONTACTS.forEach { defaultName ->
            known.removeAll { it.equals(defaultName, ignoreCase = true) }
            allowed.removeAll { it.equals(defaultName, ignoreCase = true) }
            known.add(defaultName)
            allowed.add(defaultName)
        }

        p.edit()
            .putBoolean(KEY_FILTER_ENABLED, true)
            .putStringSet(KEY_KNOWN, known)
            .putStringSet(KEY_ALLOWED, allowed)
            .putBoolean(KEY_DEFAULTS_INITIALIZED, true)
            .apply()
    }

    fun isFilterEnabled(context: Context): Boolean {
        ensureDefaults(context)
        return prefs(context).getBoolean(KEY_FILTER_ENABLED, true)
    }

    fun setFilterEnabled(context: Context, enabled: Boolean) {
        ensureDefaults(context)
        prefs(context).edit().putBoolean(KEY_FILTER_ENABLED, enabled).apply()
    }

    fun registerSeen(context: Context, name: String) {
        ensureDefaults(context)
        val value = clean(name)
        if (value.isBlank()) return
        val removed = prefs(context).getStringSet(KEY_REMOVED, emptySet())?.orEmpty().orEmpty()
        if (removed.any { it.equals(value, ignoreCase = true) }) return
        val set = knownContacts(context).toMutableSet()
        if (set.none { it.equals(value, ignoreCase = true) }) {
            set.add(value)
            prefs(context).edit().putStringSet(KEY_KNOWN, set).apply()
        }
    }

    fun knownContacts(context: Context): Set<String> {
        ensureDefaults(context)
        return prefs(context).getStringSet(KEY_KNOWN, emptySet())?.toSet().orEmpty()
    }

    fun allowedContacts(context: Context): Set<String> {
        ensureDefaults(context)
        return prefs(context).getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()
    }

    fun setAllowed(context: Context, name: String, allowed: Boolean) {
        ensureDefaults(context)
        val value = clean(name)
        if (value.isBlank()) return

        if (allowed) {
            val removed = prefs(context).getStringSet(KEY_REMOVED, emptySet())?.toMutableSet() ?: mutableSetOf()
            removed.removeAll { it.equals(value, ignoreCase = true) }
            prefs(context).edit().putStringSet(KEY_REMOVED, removed).apply()
        }

        registerSeen(context, value)
        val set = allowedContacts(context).toMutableSet()
        set.removeAll { it.equals(value, ignoreCase = true) }
        if (allowed) set.add(value)
        prefs(context).edit().putStringSet(KEY_ALLOWED, set).apply()
    }

    fun removeContact(context: Context, name: String) {
        ensureDefaults(context)
        val value = clean(name)
        if (value.isBlank()) return
        val known = knownContacts(context).toMutableSet().apply {
            removeAll { it.equals(value, ignoreCase = true) }
        }
        val allowed = allowedContacts(context).toMutableSet().apply {
            removeAll { it.equals(value, ignoreCase = true) }
        }
        val removed = prefs(context).getStringSet(KEY_REMOVED, emptySet())?.toMutableSet() ?: mutableSetOf()
        removed.removeAll { it.equals(value, ignoreCase = true) }
        removed.add(value)
        prefs(context).edit()
            .putStringSet(KEY_KNOWN, known)
            .putStringSet(KEY_ALLOWED, allowed)
            .putStringSet(KEY_REMOVED, removed)
            .apply()
    }

    fun isAllowed(context: Context, name: String): Boolean {
        ensureDefaults(context)
        if (!isFilterEnabled(context)) return true
        val normalized = clean(name).lowercase(Locale.ROOT)
        return allowedContacts(context).any { it.trim().lowercase(Locale.ROOT) == normalized }
    }
}
