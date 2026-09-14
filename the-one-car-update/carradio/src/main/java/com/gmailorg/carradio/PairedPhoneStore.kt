package com.gmailorg.carradio

import android.content.Context

/**
 * Onthoudt welk gekoppeld Bluetooth-apparaat de telefoon is waarmee deze
 * autoradio moet verbinden voor de WhatsApp-koppeling. De autoradio is nu
 * de "client" die zelf de verbinding opzet (de telefoon luistert) — dit
 * apparaat wordt gekozen uit de op de hoofdunit al gekoppelde apparaten.
 */
object PairedPhoneStore {
    private const val PREFS = "paired_phone_prefs"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    fun setSelected(context: Context, address: String, name: String) {
        prefs(context).edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name)
            .apply()
    }

    fun selectedAddress(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)

    fun selectedName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
