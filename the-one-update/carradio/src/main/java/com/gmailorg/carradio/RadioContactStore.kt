package com.gmailorg.carradio

import android.content.Context

object RadioContactStore {
    private const val PREFS = "the_one_car_contacts"
    private const val KEY_KNOWN = "known"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_FILTER_ENABLED = "filter_enabled"
    private const val KEY_DEFAULTS_INITIALIZED = "defaults_initialized_v4_fixed"
    private const val KEY_PENDING_ON = "pending_on"
    private const val KEY_PENDING_OFF = "pending_off"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_DEFAULTS_INITIALIZED, false)) return

        val oldKnown = p.getStringSet(KEY_KNOWN, emptySet())?.toSet().orEmpty()
        val oldAllowed = p.getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()
        val hadExistingConfig = oldKnown.isNotEmpty() || oldAllowed.isNotEmpty()
        val fixed = ContactAliases.defaultRealNames

        val allowed = linkedSetOf<String>()
        fixed.forEach { name ->
            val wasKnown = oldKnown.any { it.equals(name, ignoreCase = true) }
            val wasAllowed = oldAllowed.any { it.equals(name, ignoreCase = true) }
            if (!hadExistingConfig || !wasKnown || wasAllowed) allowed.add(name)
        }

        p.edit()
            .putStringSet(KEY_KNOWN, fixed)
            .putStringSet(KEY_ALLOWED, allowed)
            .putBoolean(KEY_FILTER_ENABLED, true)
            .putBoolean(KEY_DEFAULTS_INITIALIZED, true)
            .apply()
    }

    fun resetForReconnect(context: Context) {
        ensureDefaults(context)
        val p = prefs(context)
        val currentAllowed = p.getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()
        val fixed = ContactAliases.defaultRealNames
        val fixedAllowed = fixed.filterTo(linkedSetOf()) { name ->
            currentAllowed.any { it.equals(name, ignoreCase = true) }
        }
        p.edit()
            .putStringSet(KEY_KNOWN, fixed)
            .putStringSet(KEY_ALLOWED, fixedAllowed)
            .putBoolean(KEY_FILTER_ENABLED, true)
            .apply()
    }

    fun known(context: Context): Set<String> {
        ensureDefaults(context)
        return ContactAliases.defaultRealNames
    }

    fun allowed(context: Context): Set<String> {
        ensureDefaults(context)
        val stored = prefs(context).getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()
        return ContactAliases.defaultRealNames.filterTo(linkedSetOf()) { name ->
            stored.any { it.equals(name, ignoreCase = true) }
        }
    }

    fun filterEnabled(context: Context): Boolean {
        ensureDefaults(context)
        return true
    }

    fun setFilterEnabled(context: Context, enabled: Boolean) {
        ensureDefaults(context)
        prefs(context).edit().putBoolean(KEY_FILTER_ENABLED, true).apply()
    }

    /** Start van een volledige snapshot vanaf de telefoon. */
    fun beginSync(context: Context, enabled: Boolean) {
        ensureDefaults(context)
        // Snapshot mag alleen vaste contacten bevatten. We legen tijdelijk de selectie;
        // CONTACT-regels vullen hem direct weer met de actuele telefoonkeuzes.
        prefs(context).edit()
            .putBoolean(KEY_FILTER_ENABLED, true)
            .putStringSet(KEY_KNOWN, ContactAliases.defaultRealNames)
            .putStringSet(KEY_ALLOWED, emptySet())
            .apply()
    }

    fun registerKnown(context: Context, name: String) {
        ensureDefaults(context)
        // Losse gesprekken worden niet opgeslagen in het vaste-contactenscherm.
        if (!ContactAliases.isFixed(name)) return
    }

    fun putContact(context: Context, name: String, isAllowed: Boolean) {
        ensureDefaults(context)
        val fixedName = ContactAliases.canonicalRealName(name) ?: return
        val allowed = allowed(context).toMutableSet().apply {
            removeAll { it.equals(fixedName, ignoreCase = true) }
            if (isAllowed) add(fixedName)
        }
        prefs(context).edit()
            .putStringSet(KEY_KNOWN, ContactAliases.defaultRealNames)
            .putStringSet(KEY_ALLOWED, allowed)
            .putBoolean(KEY_FILTER_ENABLED, true)
            .apply()
    }

    fun setAllowedLocal(context: Context, name: String, isAllowed: Boolean) =
        putContact(context, name, isAllowed)

    /**
     * Onthoud een wijziging die op de autoradio is gemaakt terwijl de telefoon
     * niet bereikbaar was. Bij de volgende verbinding wordt alleen deze lokale
     * wijziging naar de telefoon gestuurd; andere telefoonkeuzes blijven leidend.
     */
    fun markPending(context: Context, name: String, isAllowed: Boolean) {
        ensureDefaults(context)
        val fixedName = ContactAliases.canonicalRealName(name) ?: return
        val p = prefs(context)
        val on = p.getStringSet(KEY_PENDING_ON, emptySet())?.toMutableSet().orEmpty()
        val off = p.getStringSet(KEY_PENDING_OFF, emptySet())?.toMutableSet().orEmpty()
        on.removeAll { it.equals(fixedName, ignoreCase = true) }
        off.removeAll { it.equals(fixedName, ignoreCase = true) }
        if (isAllowed) on.add(fixedName) else off.add(fixedName)
        p.edit().putStringSet(KEY_PENDING_ON, on).putStringSet(KEY_PENDING_OFF, off).apply()
    }

    fun clearPending(context: Context, name: String) {
        ensureDefaults(context)
        val fixedName = ContactAliases.canonicalRealName(name) ?: return
        val p = prefs(context)
        val on = p.getStringSet(KEY_PENDING_ON, emptySet())?.toMutableSet().orEmpty()
        val off = p.getStringSet(KEY_PENDING_OFF, emptySet())?.toMutableSet().orEmpty()
        on.removeAll { it.equals(fixedName, ignoreCase = true) }
        off.removeAll { it.equals(fixedName, ignoreCase = true) }
        p.edit().putStringSet(KEY_PENDING_ON, on).putStringSet(KEY_PENDING_OFF, off).apply()
    }

    fun pending(context: Context): List<Pair<String, Boolean>> {
        ensureDefaults(context)
        val p = prefs(context)
        val on = p.getStringSet(KEY_PENDING_ON, emptySet())?.toSet().orEmpty()
        val off = p.getStringSet(KEY_PENDING_OFF, emptySet())?.toSet().orEmpty()
        val result = mutableListOf<Pair<String, Boolean>>()
        ContactAliases.defaultRealNames.forEach { fixed ->
            when {
                on.any { it.equals(fixed, ignoreCase = true) } -> result += fixed to true
                off.any { it.equals(fixed, ignoreCase = true) } -> result += fixed to false
            }
        }
        return result
    }

    fun removeLocal(context: Context, name: String) {
        // Vaste contacten worden niet verwijderd; zet ze uit met setAllowedLocal().
        resetForReconnect(context)
    }
}
