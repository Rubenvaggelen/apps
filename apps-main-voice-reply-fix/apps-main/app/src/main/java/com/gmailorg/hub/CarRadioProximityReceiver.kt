package com.gmailorg.hub

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Houdt bij of de gekozen autoradio daadwerkelijk in Bluetooth-bereik is
 * (ACL-verbinding aan/uit), zodat "WhatsApp naar autoradio" alleen actief
 * probeert te verbinden zolang je echt in of bij de auto bent — en meteen
 * stopt (inclusief de "verbinden..."-melding) zodra je wegloopt of wegrijdt,
 * in plaats van eindeloos elke paar seconden te blijven proberen.
 */
class CarRadioProximityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (!CarRadioForwarder.isEnabled(appContext)) return

        val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        val address = device?.address ?: return
        if (address != CarRadioForwarder.selectedDeviceAddress(appContext)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                CarRadioForwarder.setNearby(appContext, true)
                CarRadioConnectionService.start(appContext)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                CarRadioForwarder.setNearby(appContext, false)
                CarRadioConnectionService.stop(appContext)
            }
        }
    }
}
