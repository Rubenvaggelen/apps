package com.gmailorg.hub

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Houdt alleen de fysieke Bluetooth-nabijheidsstatus bij. De eigen The One
 * RFCOMM-server blijft draaien zolang "WhatsApp naar autoradio" aan staat,
 * zodat de autoradio na contact/boot altijd zelfstandig kan terugverbinden.
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
        val selected = CarRadioForwarder.selectedDeviceAddress(appContext)
        if (selected != null && address != selected) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                CarRadioForwarder.setNearby(appContext, true)
                // Idempotent: als de service al draait gebeurt er niets extra's.
                try { CarRadioConnectionService.start(appContext) } catch (_: Exception) {}
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                CarRadioForwarder.setNearby(appContext, false)
                // BELANGRIJK: niet meer stoppen. De server blijft luisteren,
                // zodat de radio bij de volgende autorit direct kan terugverbinden.
            }
        }
    }
}
