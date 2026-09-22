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
            // Na een telefoonherstart moet de The One Car-server weer luisteren.
            // De radio verbindt pas wanneer de hotspot bereikbaar is; de server mag
            // daarom al klaarstaan zonder Bluetooth- of hotspot-detectie als voorwaarde.
            CarRadioForwarder.setNearby(appContext, false)
            if (CarRadioForwarder.isEnabled(appContext)) {
                try { CarRadioConnectionService.start(appContext) } catch (_: Exception) {}
            }
        }
    }
}
