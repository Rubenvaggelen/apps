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
    private const val KEY_NEARBY = "nearby"

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

    /**
     * Of de gekozen autoradio momenteel (Bluetooth-ACL) verbonden is, dus of
     * de telefoon zich waarschijnlijk in of bij de auto bevindt. Wordt
     * bijgewerkt door CarRadioProximityReceiver zodra Android een
     * verbinding/loskoppeling van dat apparaat meldt.
     */
    fun isNearby(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NEARBY, false)

    fun setNearby(context: Context, nearby: Boolean) {
        prefs(context).edit().putBoolean(KEY_NEARBY, nearby).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Stuurt een WhatsApp-melding door, indien aangezet en een autoradio
     * gekoppeld is. Er wordt hier bewust niet meer gecheckt of de auto
     * "in de buurt" is (die aparte vlag bleek soms verouderd te blijven
     * staan, bijvoorbeeld na een app-update, en blokkeerde dan het
     * versturen ondanks een prima werkende verbinding) — sendMessage() doet
     * zelf al niets als er geen actieve verbinding is, dus er is geen risico
     * dat er iets verstuurd wordt terwijl je niet in de auto bent.
     */
    fun forwardIfEnabled(context: Context, packageName: String, title: String, text: String) {
        if (packageName != "com.whatsapp") return
        if (!isEnabled(context)) return

        // De radio is zelf de client en wordt door de app-handshake geverifieerd.
        // Een lokaal opgeslagen device-adres op de telefoon is daarom niet nodig
        // om meldingen door te sturen; na een update/herinstallatie kon die oude
        // voorkeur leeg zijn en dan werden geldige meldingen ten onrechte geblokkeerd.

        // Best effort: als de verbindingsservice om wat voor reden niet
        // draait, zorg dat hij alsnog opstart; sendMessage() stuurt sowieso
        // alleen iets als er daadwerkelijk een actieve verbinding is.
        CarRadioConnectionService.start(context)
        CarRadioConnectionService.sendMessage("$title: $text")
    }
}
