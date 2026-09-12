package com.gmailorg.carradio

import android.content.Context

object CarTileStore {
    private const val PREFS = "car_tiles"
    private const val KEY_HIDDEN = "hidden_fixed"
    private const val KEY_APPS = "user_apps"
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
}
