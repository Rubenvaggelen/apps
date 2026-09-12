package com.gmailorg.carradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Start de Bluetooth-client direct na het opstarten van de hoofdunit. We zijn
 * niet meer afhankelijk van MainActivity.onCreate(): sommige autoradio's
 * herstellen hun launcher/UI zonder de activity opnieuw te creëren.
 */
class CarRadioBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, BluetoothListenerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // MainActivity probeert het bij openen nogmaals.
        }

        // Bij een echte boot ook de UI tonen. Een kleine vertraging geeft de
        // Bluetooth-stack tijd om wakker te worden; de service zelf blijft
        // ondertussen automatisch opnieuw proberen.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    appContext.startActivity(launchIntent)
                } catch (_: Exception) {
                    // Geen probleem: service blijft op de achtergrond verbinden.
                }
            }, 2500L)
        }
    }
}
