package com.gmailorg.hub

import android.content.Context
import java.util.Locale

/**
 * Vaste WhatsApp-contacten voor The One Car.
 *
 * De lijst is bewust gesloten: tijdelijke/losse gesprekken worden niet meer
 * automatisch toegevoegd. Alleen de vaste contacten kunnen aan/uit worden gezet.
 */
object WhatsAppCarFilterStore {
    private const val PREFS = "whatsapp_car_filter"
    private const val KEY_FILTER_ENABLED = "filter_enabled"
    private const val KEY_KNOWN = "known_contacts"
    private const val KEY_ALLOWED = "allowed_contacts"
    private const val KEY_DEFAULTS_INITIALIZED = "defaults_initialized_v4_fixed"

    /** Echte WhatsApp-namen die als vaste contacten worden gebruikt. */
    val FIXED_CONTACTS = linkedSetOf(
        "Beyonce aka FARQUAAD Van Aggelen", // Dochter
        "Susan Oehlers",                    // Ma
        "SST",                              // CA
        "Ruben Werk Tel",                   // Test
        "Devon",
        "Envy"
    )

    /** Compatibiliteit met oudere code/bestanden. */
    val DEFAULT_ALLOWED_CONTACTS: Set<String> get() = FIXED_CONTACTS

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clean(name: String): String = name.trim()

    private fun canonicalFixedName(name: String): String? {
        val clean = clean(name)
        return FIXED_CONTACTS.firstOrNull { it.equals(clean, ignoreCase = true) }
    }

    private fun ensureDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_DEFAULTS_INITIALIZED, false)) return

        val oldKnown = p.getStringSet(KEY_KNOWN, emptySet())?.toSet().orEmpty()
        val oldAllowed = p.getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()
        val hadExistingConfig = oldKnown.isNotEmpty() || oldAllowed.isNotEmpty()

        val allowed = linkedSetOf<String>()
        FIXED_CONTACTS.forEach { fixed ->
            val wasKnown = oldKnown.any { it.equals(fixed, ignoreCase = true) }
            val wasAllowed = oldAllowed.any { it.equals(fixed, ignoreCase = true) }
            // Bestaande aan/uit-keuzes behouden. Nieuwe vaste contacten standaard aan.
            if (!hadExistingConfig || !wasKnown || wasAllowed) allowed.add(fixed)
        }

        p.edit()
            .putBoolean(KEY_FILTER_ENABLED, true)
            .putStringSet(KEY_KNOWN, FIXED_CONTACTS.toSet())
            .putStringSet(KEY_ALLOWED, allowed)
            .putBoolean(KEY_DEFAULTS_INITIALIZED, true)
            .remove("removed_contacts")
            .apply()
    }

    /**
     * Wordt bij iedere nieuwe autoradioverbinding uitgevoerd.
     * Alle niet-vaste contacten verdwijnen, terwijl de aan/uit-keuze van vaste
     * contacten behouden blijft.
     */
    fun resetForReconnect(context: Context) {
        ensureDefaults(context)
        val p = prefs(context)
        val currentAllowed = p.getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()
        val fixedAllowed = FIXED_CONTACTS.filterTo(linkedSetOf()) { fixed ->
            currentAllowed.any { it.equals(fixed, ignoreCase = true) }
        }
        p.edit()
            .putBoolean(KEY_FILTER_ENABLED, true)
            .putStringSet(KEY_KNOWN, FIXED_CONTACTS.toSet())
            .putStringSet(KEY_ALLOWED, fixedAllowed)
            .remove("removed_contacts")
            .apply()
    }

    fun isFilterEnabled(context: Context): Boolean {
        ensureDefaults(context)
        // Vaste-contactmodus kan niet globaal uitgezet worden; alleen per contact.
        return true
    }

    fun setFilterEnabled(context: Context, enabled: Boolean) {
        ensureDefaults(context)
        // Bewust altijd aan. De individuele vaste contacten zijn de selectieknoppen.
        prefs(context).edit().putBoolean(KEY_FILTER_ENABLED, true).apply()
    }

    fun registerSeen(context: Context, name: String) {
        ensureDefaults(context)
        // Losse WhatsApp-contacten/groepen niet meer onthouden.
        canonicalFixedName(name) ?: return
    }

    fun knownContacts(context: Context): Set<String> {
        ensureDefaults(context)
        return FIXED_CONTACTS.toSet()
    }

    fun allowedContacts(context: Context): Set<String> {
        ensureDefaults(context)
        val stored = prefs(context).getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()
        return FIXED_CONTACTS.filterTo(linkedSetOf()) { fixed ->
            stored.any { it.equals(fixed, ignoreCase = true) }
        }
    }

    fun setAllowed(context: Context, name: String, allowed: Boolean) {
        ensureDefaults(context)
        val fixed = canonicalFixedName(name) ?: return
        val set = allowedContacts(context).toMutableSet()
        set.removeAll { it.equals(fixed, ignoreCase = true) }
        if (allowed) set.add(fixed)
        prefs(context).edit()
            .putBoolean(KEY_FILTER_ENABLED, true)
            .putStringSet(KEY_KNOWN, FIXED_CONTACTS.toSet())
            .putStringSet(KEY_ALLOWED, set)
            .apply()
    }

    fun removeContact(context: Context, name: String) {
        ensureDefaults(context)
        // Vaste contacten kunnen niet worden verwijderd. Uitzetten kan via setAllowed().
        resetForReconnect(context)
    }

    fun isAllowed(context: Context, name: String): Boolean {
        ensureDefaults(context)
        val fixed = canonicalFixedName(name) ?: return false
        val normalized = fixed.lowercase(Locale.ROOT)
        return allowedContacts(context).any { it.trim().lowercase(Locale.ROOT) == normalized }
    }
}
