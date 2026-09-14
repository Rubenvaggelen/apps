package com.gmailorg.carradio

import android.content.Context

object CarTileStore {
    private const val PREFS = "car_tiles"
    private const val KEY_HIDDEN = "hidden_fixed"
    private const val KEY_APPS = "user_apps"
    private const val KEY_ORDER = "dashboard_order"
    private const val ORDER_SEPARATOR = "\n"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hidden(context: Context): Set<String> = prefs(context).getStringSet(KEY_HIDDEN, emptySet())?.toSet().orEmpty()
    fun hide(context: Context, id: String) {
        val set = hidden(context).toMutableSet(); set.add(id); prefs(context).edit().putStringSet(KEY_HIDDEN, set).apply()
    }
    fun restore(context: Context, id: String) {
        val set = hidden(context).toMutableSet(); set.remove(id); prefs(context).edit().putStringSet(KEY_HIDDEN, set).apply()
    }
    fun restoreAll(context: Context) = prefs(context).edit().remove(KEY_HIDDEN).apply()

    fun apps(context: Context): Set<String> = prefs(context).getStringSet(KEY_APPS, emptySet())?.toSet().orEmpty()
    fun addApp(context: Context, pkg: String) {
        val set = apps(context).toMutableSet(); set.add(pkg); prefs(context).edit().putStringSet(KEY_APPS, set).apply()
    }
    fun removeApp(context: Context, pkg: String) {
        val set = apps(context).toMutableSet(); set.remove(pkg); prefs(context).edit().putStringSet(KEY_APPS, set).apply()
    }

    /** Dashboardvolgorde. Sleutels zijn bijvoorbeeld fixed:music of app:com.spotify.music. */
    fun savedOrder(context: Context): List<String> =
        prefs(context).getString(KEY_ORDER, "")
            .orEmpty()
            .split(ORDER_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * Geeft alle zichtbare sleutels terug in de opgeslagen volgorde. Nieuwe tegels
     * worden achteraan toegevoegd zodat bestaande persoonlijke indeling intact blijft.
     */
    fun orderedKeys(context: Context, visibleKeys: List<String>): List<String> {
        val visible = visibleKeys.distinct()
        val visibleSet = visible.toSet()
        val saved = savedOrder(context).filter { it in visibleSet }
        return saved + visible.filterNot { it in saved.toSet() }
    }

    fun saveOrder(context: Context, keys: List<String>) {
        prefs(context).edit().putString(KEY_ORDER, keys.distinct().joinToString(ORDER_SEPARATOR)).apply()
    }
}
