package com.gmailorg.carradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Start "The One" automatisch zodra de hoofdunit is opgestart, zodat de app
 * altijd meteen open staat zonder dat de gebruiker 'm zelf moet aantikken.
 */
class CarRadioBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(launchIntent)
        }
    }
}
