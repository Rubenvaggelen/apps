package com.gmailorg.hub

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.OutputStream
import java.util.UUID

/**
 * Stuurt WhatsApp-meldingen door naar de gekoppelde autoradio via Bluetooth
 * (RFCOMM) — maar alleen als de gebruiker dit zelf heeft aangezet
 * (Instellingen > "WhatsApp naar autoradio"), nooit automatisch.
 */
object CarRadioForwarder {

    // Moet exact overeenkomen met BluetoothListenerService.APP_UUID in de carradio-module.
    private val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
    private const val TAG = "CarRadioForwarder"

    private const val PREFS = "car_radio_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_NAME = "device_name"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setSelectedDevice(context: Context, address: String, name: String) {
        prefs(context).edit()
            .putString(KEY_DEVICE_ADDRESS, address)
            .putString(KEY_DEVICE_NAME, name)
            .apply()
    }

    fun selectedDeviceAddress(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ADDRESS, null)

    fun selectedDeviceName(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_NAME, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stuurt een WhatsApp-melding door, indien aangezet en er een autoradio gekoppeld is. */
    fun forwardIfEnabled(context: Context, packageName: String, title: String, text: String) {
        if (packageName != "com.whatsapp") return
        if (!isEnabled(context)) return
        val address = selectedDeviceAddress(context) ?: return

        Thread {
            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
                val device = adapter.getRemoteDevice(address)
                socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                val out: OutputStream = socket.outputStream
                out.write("$title: $text\n".toByteArray())
                out.flush()
            } catch (e: Exception) {
                // Geen verbinding (bv. niet in de auto op dit moment) — gewoon negeren,
                // dit is bewust "best effort", geen melding die de gebruiker moet zien.
                Log.w(TAG, "Kon melding niet doorsturen naar autoradio", e)
            } finally {
                try { socket?.close() } catch (e: Exception) { /* negeren */ }
            }
        }.start()
    }
}
