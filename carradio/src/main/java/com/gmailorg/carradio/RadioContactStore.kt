package com.gmailorg.carradio

import android.content.Context

object RadioContactStore {
    private const val PREFS = "the_one_car_contacts"
    private const val KEY_KNOWN = "known"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_FILTER_ENABLED = "filter_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun known(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_KNOWN, emptySet())?.toSet().orEmpty()

    fun allowed(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED, emptySet())?.toSet().orEmpty()

    fun filterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILTER_ENABLED, true)

    fun setFilterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FILTER_ENABLED, enabled).apply()
    }

    fun beginSync(context: Context, enabled: Boolean) {
        prefs(context).edit()
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
}
