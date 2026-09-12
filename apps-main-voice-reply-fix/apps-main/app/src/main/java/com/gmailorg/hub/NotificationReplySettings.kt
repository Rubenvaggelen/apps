package com.gmailorg.hub

import android.content.Context

/**
 * Bepaalt of je vanuit de Meldingen-tegel op berichten mag reageren.
 * Staat standaard uit. Dit is een instelling per toestel (SharedPreferences
 * leven lokaal op elk apparaat) — als je 'm aanzet op de hoofdunit in de
 * auto, heeft dat geen effect op je telefoon, en andersom.
 */
object NotificationReplySettings {

    private const val PREFS = "notification_reply_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
