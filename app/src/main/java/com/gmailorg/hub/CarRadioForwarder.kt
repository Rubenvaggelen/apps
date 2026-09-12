package com.gmailorg.hub

import android.content.Context

/** Stuurt WhatsApp naar de radio. De ConnectionService buffert berichten tijdens radio-boot/reconnect. */
object CarRadioForwarder {
    private const val PREFS = "car_radio_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_NEARBY = "nearby"
    private const val KEY_NEARBY_AT = "nearby_at"
    // ACL_CONNECTED is only a hint. If Android misses ACL_DISCONNECTED, do not
    // keep treating the car as nearby forever. A real The One connection
    // remains authoritative via CarRadioConnectionService.isRadioConnected().
    private const val NEARBY_HINT_TTL_MS = 3 * 60 * 1000L

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            CarRadioConnectionService.stop(context)
        } else if (isNearby(context)) {
            // Alleen een foreground-service tonen wanneer de gekozen autoradio echt in bereik is.
            CarRadioConnectionService.start(context)
        }
    }

    fun setSelectedDevice(context: Context, address: String, name: String) {
        prefs(context).edit().putString(KEY_DEVICE_ADDRESS, address).putString(KEY_DEVICE_NAME, name).apply()
    }
    fun selectedDeviceAddress(context: Context): String? = prefs(context).getString(KEY_DEVICE_ADDRESS, null)
    fun selectedDeviceName(context: Context): String? = prefs(context).getString(KEY_DEVICE_NAME, null)
    fun isNearby(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_NEARBY, false)) return false
        val at = p.getLong(KEY_NEARBY_AT, 0L)
        // Old builds did not store a timestamp. Treat that old persisted true as stale.
        if (at <= 0L || System.currentTimeMillis() - at > NEARBY_HINT_TTL_MS) {
            p.edit().putBoolean(KEY_NEARBY, false).remove(KEY_NEARBY_AT).apply()
            return false
        }
        return true
    }

    fun setNearby(context: Context, nearby: Boolean) {
        val e = prefs(context).edit().putBoolean(KEY_NEARBY, nearby)
        if (nearby) e.putLong(KEY_NEARBY_AT, System.currentTimeMillis())
        else e.remove(KEY_NEARBY_AT)
        e.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun forwardIfEnabled(context: Context, packageName: String, title: String, text: String, postTime: Long = System.currentTimeMillis()) {
        if (packageName != "com.whatsapp" || !isEnabled(context)) return
        WhatsAppCarFilterStore.registerSeen(context, title)

        // Buiten de auto niets starten of bufferen. Anders blijft Android een
        // foreground-melding "Wacht op autoradio" tonen terwijl de radio niet in de buurt is.
        val radioAvailable = isNearby(context) || CarRadioConnectionService.isRadioConnected()
        if (!radioAvailable) return

        if (!CarRadioConnectionService.isRadioConnected()) {
            CarRadioConnectionService.start(context)
        }
        val allowed = WhatsAppCarFilterStore.isAllowed(context, title)
        CarRadioConnectionService.sendContactState(
            title,
            WhatsAppCarFilterStore.allowedContacts(context).any { it.equals(title, ignoreCase = true) },
            WhatsAppCarFilterStore.isFilterEnabled(context)
        )
        if (!allowed) return
        CarRadioConnectionService.sendWhatsAppMessage(title, text, postTime)
    }
}
