package com.gmailorg.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val appContext = context.applicationContext
            SupermarketGeofenceManager.reArmAfterBootIfEnabled(appContext)
            if (SupermarketGeofenceManager.isEnabled(appContext)) {
                SupermarketRefreshWorker.schedule(appContext)
            }
            ParkingGeofenceManager.syncAll(appContext)
            // Niet meer blindelings starten bij opstarten: we weten na een
            // herstart niet zeker of de auto al in bereik is. De
            // CarRadioProximityReceiver start de verbinding vanzelf zodra
            // Android een ACL-verbinding met de gekozen autoradio meldt.
            CarRadioForwarder.setNearby(appContext, false)
        }
    }
}
