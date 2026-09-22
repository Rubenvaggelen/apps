package com.gmailorg.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Start de The One Car server zodra Android meldt dat tethering/hotspot actief wordt. */
class CarHotspotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        CarHotspotDetector.updateFromBroadcast(app, intent)
        if (!CarRadioForwarder.isEnabled(app)) return
        if (!CarHotspotDetector.isHotspotLikelyActive(app)) return
        if (!CarRadioForwarder.isNearby(app) && !CarRadioConnectionService.isRadioConnected()) return
        try {
            CarRadioConnectionService.start(app)
        } catch (_: Exception) {}
    }
}
