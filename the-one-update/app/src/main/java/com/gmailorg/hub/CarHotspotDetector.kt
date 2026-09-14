package com.gmailorg.hub

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** Detecteert zo betrouwbaar mogelijk of de telefoon zelf als Wi-Fi-hotspot/tether fungeert. */
object CarHotspotDetector {
    private const val WIFI_AP_STATE_DISABLED = 11
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val PREFS = "car_hotspot_hint"
    private const val KEY_ACTIVE = "active"
    private const val KEY_AT = "at"
    private const val HINT_TTL_MS = 6 * 60 * 60 * 1000L

    /** Bewaar de systeem-broadcast als extra hint voor toestellen die hidden API's blokkeren. */
    fun updateFromBroadcast(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        var known: Boolean? = null

        if (action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
            val state = intent?.getIntExtra("wifi_state", -1) ?: -1
            known = when (state) {
                WIFI_AP_STATE_ENABLED -> true
                WIFI_AP_STATE_DISABLED -> false
                else -> null
            }
        } else if (action == "android.net.conn.TETHER_STATE_CHANGED") {
            try {
                val active = intent?.getStringArrayListExtra("activeArray")
                if (active != null) known = active.isNotEmpty()
            } catch (_: Exception) {}
        }

        if (known != null) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ACTIVE, known).putLong(KEY_AT, System.currentTimeMillis()).apply()
        }
    }

    fun isHotspotLikelyActive(context: Context): Boolean {
        val app = context.applicationContext

        // Op veel Samsung-toestellen werkt deze status nog direct.
        try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null) {
                val method = wifi.javaClass.methods.firstOrNull { it.name == "getWifiApState" && it.parameterTypes.isEmpty() }
                val state = (method?.invoke(wifi) as? Int)
                if (state == WIFI_AP_STATE_ENABLED) return true
                if (state == WIFI_AP_STATE_DISABLED) return false
            }
        } catch (_: Exception) {}

        // Systeem-broadcast-hint: voorkomt dat een hotspotverbinding gemist wordt
        // wanneer Samsung/Android toegang tot getWifiApState beperkt.
        try {
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val at = p.getLong(KEY_AT, 0L)
            if (at > 0L && System.currentTimeMillis() - at <= HINT_TTL_MS) {
                if (p.getBoolean(KEY_ACTIVE, false)) return true
            }
        } catch (_: Exception) {}

        // Fallback: zoek een actieve tether-interface (o.a. ap0/swlan0 op Samsung).
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.orEmpty().lowercase()
                val looksLikeTether = name.startsWith("ap") ||
                    name.contains("softap") || name.contains("swlan") ||
                    name.contains("tether") || name.contains("rndis")
                if (!looksLikeTether) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
