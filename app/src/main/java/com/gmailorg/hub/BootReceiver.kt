package com.gmailorg.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Houdt de autoradio-server betrouwbaar beschikbaar na een telefoonboot of
 * app-update. De radio is de client en kan alleen automatisch terugverbinden
 * als de telefoon al op RFCOMM luistert.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                SupermarketGeofenceManager.reArmAfterBootIfEnabled(appContext)
                if (SupermarketGeofenceManager.isEnabled(appContext)) {
                    SupermarketRefreshWorker.schedule(appContext)
                }
                ParkingGeofenceManager.syncAll(appContext)

                // "nearby" is alleen informatief. Stop de RFCOMM-server niet:
                // na het starten van de auto wordt ACTION_ACL_CONNECTED op
                // sommige telefoons/head-units niet betrouwbaar opnieuw gestuurd.
                CarRadioForwarder.setNearby(appContext, false)
                if (CarRadioForwarder.isEnabled(appContext)) {
                    try {
                        CarRadioConnectionService.start(appContext)
                    } catch (_: Exception) {
                        // Best effort; de NotificationListener en een nieuwe
                        // WhatsApp-melding proberen het later nogmaals.
                    }
                }
            }
        }
    }
}
