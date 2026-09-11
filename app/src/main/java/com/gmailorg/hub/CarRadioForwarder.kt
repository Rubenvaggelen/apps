package com.gmailorg.hub

import android.content.Context

/**
 * Stuurt WhatsApp-meldingen door naar de gekoppelde autoradio via Bluetooth
 * (RFCOMM) — maar alleen als de gebruiker dit zelf heeft aangezet
 * (Instellingen > "WhatsApp naar autoradio"), nooit automatisch.
 * De eigenlijke verbinding wordt onderhouden door CarRadioConnectionService.
 */
object CarRadioForwarder {

    private const val PREFS = "car_radio_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_NAME = "device_name"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setSelectedDevice(context: Context, address: String, name: String) {
        prefs(context).edit()
            .putString(KEY_DEVICE_ADDRESS, address)
            .putString(KEY_DEVICE_NAME, name)
            .apply()
    }

    fun selectedDeviceAddress(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ADDRESS, null)

    fun selectedDeviceName(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_NAME, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stuurt een WhatsApp-melding door, indien aangezet en er een autoradio gekoppeld is. */
    fun forwardIfEnabled(context: Context, packageName: String, title: String, text: String) {
        if (packageName != "com.whatsapp") return
        if (!isEnabled(context)) return
        if (selectedDeviceAddress(context) == null) return

        // Best effort: als de verbindingsservice om wat voor reden niet draait
        // (bv. na een reboot), zorg dat hij alsnog opstart.
        CarRadioConnectionService.start(context)
        CarRadioConnectionService.sendMessage("$title: $text")
    }
}
