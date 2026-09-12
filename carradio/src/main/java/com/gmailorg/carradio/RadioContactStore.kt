package com.gmailorg.carradio

import android.content.Context

object RadioContactStore {
    private const val PREFS = "the_one_car_contacts"
    private const val KEY_KNOWN = "known"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_FILTER_ENABLED = "filter_enabled"
    private const val KEY_DEFAULTS_INITIALIZED = "defaults_initialized_v3"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Voeg de standaardcontacten één keer toe. Daarna mag de gebruiker ze echt verwijderen;
     * ze worden dan niet bij iedere schermrefresh opnieuw toegevoegd.
     */
    private fun ensureDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_DEFAULTS_INITIALIZED, false)) return

        val known = p.getStringSet(KEY_KNOWN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val allowed = p.getStringSet(KEY_ALLOWED, emptySet())?.toMutableSet() ?: mutableSetOf()
        ContactAliases.defaultRealNames.forEach { name ->
            known.removeAll { it.equals(name, ignoreCase = true) }
            allowed.removeAll { it.equals(name, ignoreCase = true) }
            known.add(name)
            allowed.add(name)
        }
        p.edit()
            .putStringSet(KEY_KNOWN, known)
            .putStringSet(KEY_ALLOWED, allowed)
            .putBoolean(KEY_FILTER_ENABLED, true)
            .putBoolean(KEY_DEFAULTS_INITIALIZED, true)
            .apply()
    }

    fun known(context: Context): Set<String> {
        ensureDefaults(context)
        return prefs(context).getStringSet(KEY_KNOWN, emptySet())?.toSet().orEmpty()
    }

    fun allowed(context: Context): Set<String> {
        ensureDefaults(context)
        return prefs(context).getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()
    }

    fun filterEnabled(context: Context): Boolean {
        ensureDefaults(context)
        return prefs(context).getBoolean(KEY_FILTER_ENABLED, true)
    }

    fun setFilterEnabled(context: Context, enabled: Boolean) {
        ensureDefaults(context)
        prefs(context).edit().putBoolean(KEY_FILTER_ENABLED, enabled).apply()
    }

    /** Start van een volledige snapshot vanaf de telefoon. */
    fun beginSync(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DEFAULTS_INITIALIZED, true)
            .putBoolean(KEY_FILTER_ENABLED, enabled)
            .putStringSet(KEY_KNOWN, emptySet())
            .putStringSet(KEY_ALLOWED, emptySet())
            .apply()
    }

    fun registerKnown(context: Context, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val known = known(context).toMutableSet().apply {
            removeAll { it.equals(clean, ignoreCase = true) }
            add(clean)
        }
        prefs(context).edit().putStringSet(KEY_KNOWN, known).apply()
    }

    fun putContact(context: Context, name: String, isAllowed: Boolean) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val known = known(context).toMutableSet().apply {
            removeAll { it.equals(clean, ignoreCase = true) }
            add(clean)
        }
        val allowed = allowed(context).toMutableSet().apply {
            removeAll { it.equals(clean, ignoreCase = true) }
            if (isAllowed) add(clean)
        }
        prefs(context).edit().putStringSet(KEY_KNOWN, known).putStringSet(KEY_ALLOWED, allowed).apply()
    }

    fun setAllowedLocal(context: Context, name: String, isAllowed: Boolean) =
        putContact(context, name, isAllowed)

    fun removeLocal(context: Context, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val known = known(context).toMutableSet().apply { removeAll { it.equals(clean, ignoreCase = true) } }
        val allowed = allowed(context).toMutableSet().apply { removeAll { it.equals(clean, ignoreCase = true) } }
        prefs(context).edit().putStringSet(KEY_KNOWN, known).putStringSet(KEY_ALLOWED, allowed).apply()
    }
}
