package com.gmailorg.carradio

import android.content.Context

/**
 * Kleine teller voor nieuwe WhatsApp-berichten op de The One Car-tegel.
 * Staat bewust los van de 'gelezen'-status in de gesprekkenlijst: zodra de
 * gebruiker Car/WhatsApp opent verdwijnt alleen de dashboardbadge.
 */
object DashboardUnreadStore {
    private const val PREFS = "the_one_car_dashboard_unread"
    private const val KEY_WHATSAPP = "whatsapp_unread"
    private const val MAX_BADGE = 999

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun count(context: Context): Int = prefs(context).getInt(KEY_WHATSAPP, 0).coerceAtLeast(0)

    fun increment(context: Context) {
        val next = (count(context) + 1).coerceAtMost(MAX_BADGE)
        prefs(context).edit().putInt(KEY_WHATSAPP, next).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().putInt(KEY_WHATSAPP, 0).apply()
    }
}
