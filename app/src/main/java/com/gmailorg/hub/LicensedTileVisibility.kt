package com.gmailorg.hub

import android.content.Context

/**
 * Presentation-only visibility for the three separate Android apps.
 * Licensing is independent: unhiding a tile never grants access.
 */
object LicensedTileVisibility {
    val labels = linkedMapOf(
        "com.theone.cinema" to "Cinema",
        "com.theone.series" to "Series",
        "com.theone.tv.phone" to "TV"
    )

    private const val PREFS = "licensed_app_tile_visibility"

    fun isVisible(context: Context, id: String): Boolean {
        if (id !in labels) return true
        if (MainDeviceRegistry.isLocallyOwner(context)) return true
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(id, false)
    }

    fun setVisible(context: Context, id: String, visible: Boolean) {
        if (id !in labels) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(id, visible).apply()
    }

    fun isHidden(context: Context, id: String): Boolean = !isVisible(context, id)
}
