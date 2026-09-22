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
            // De car-service is stil gebonden en kan dus alvast klaarstaan zonder
            // een zichtbare autoradio-melding buiten de auto.
            CarRadioForwarder.setNearby(appContext, false)
            if (CarRadioForwarder.isEnabled(appContext)) {
                try { CarRadioConnectionService.start(appContext) } catch (_: Exception) {}
            }
        }
    }
}
