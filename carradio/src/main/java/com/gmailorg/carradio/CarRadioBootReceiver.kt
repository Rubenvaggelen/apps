package com.gmailorg.carradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * K2401 boot receiver.
 *
 * De K2401 start zijn eigen launcher soms enkele seconden NA BOOT_COMPLETED.
 * Daarom laat de Bluetooth foreground service The One gedurende de eerste
 * opstartfase een paar keer opnieuw naar voren komen. Zo wint The One de race
 * van de fabriekslauncher zonder dat de gebruiker een Home-app dialoog nodig heeft.
 */
class CarRadioBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, BluetoothListenerService::class.java).apply {
            action = BluetoothListenerService.ACTION_FORCE_STARTUP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // MainActivity probeert de service opnieuw te starten zodra hij zichtbaar is.
        }
    }
}
