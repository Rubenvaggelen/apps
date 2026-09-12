package com.gmailorg.hub

import android.content.Context

/** Stuurt WhatsApp naar de radio. De ConnectionService buffert berichten tijdens radio-boot/reconnect. */
object CarRadioForwarder {
    private const val PREFS = "car_radio_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_NEARBY = "nearby"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) CarRadioConnectionService.start(context)
    }

    fun setSelectedDevice(context: Context, address: String, name: String) {
        prefs(context).edit().putString(KEY_DEVICE_ADDRESS, address).putString(KEY_DEVICE_NAME, name).apply()
    }
    fun selectedDeviceAddress(context: Context): String? = prefs(context).getString(KEY_DEVICE_ADDRESS, null)
    fun selectedDeviceName(context: Context): String? = prefs(context).getString(KEY_DEVICE_NAME, null)
    fun isNearby(context: Context): Boolean = prefs(context).getBoolean(KEY_NEARBY, false)
    fun setNearby(context: Context, nearby: Boolean) { prefs(context).edit().putBoolean(KEY_NEARBY, nearby).apply() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun forwardIfEnabled(context: Context, packageName: String, title: String, text: String, postTime: Long = System.currentTimeMillis()) {
        if (packageName != "com.whatsapp" || !isEnabled(context)) return
        WhatsAppCarFilterStore.registerSeen(context, title)
        CarRadioConnectionService.start(context)
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
