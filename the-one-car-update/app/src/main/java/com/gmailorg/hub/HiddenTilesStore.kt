package com.gmailorg.hub

import android.content.Context
import android.content.SharedPreferences

/**
 * Bewaart welke vaste tegels (Meldingen, Films/Series, enz.) de gebruiker
 * heeft verborgen van het startscherm — bijvoorbeeld omdat ze niet handig
 * zijn om te gebruiken terwijl je rijdt. Verborgen tegels zijn terug te
 * zetten via Instellingen.
 */
object HiddenTilesStore {

    private const val PREFS = "hidden_tiles"
    private const val KEY_IDS = "ids"

    private var prefs: SharedPreferences? = null
    private val hiddenIds = mutableSetOf<String>()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hiddenIds.addAll(prefs?.getStringSet(KEY_IDS, emptySet()) ?: emptySet())
    }

    fun isHidden(tileId: String): Boolean = hiddenIds.contains(tileId)

    fun hide(tileId: String) {
        hiddenIds.add(tileId)
        persist()
    }

    fun unhide(tileId: String) {
        hiddenIds.remove(tileId)
        persist()
    }

    fun getAllHidden(): Set<String> = hiddenIds.toSet()

    private fun persist() {
        prefs?.edit()?.putStringSet(KEY_IDS, hiddenIds.toSet())?.apply()
    }
}
