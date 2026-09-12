package com.gmailorg.carradio

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CarSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private val statusListener: (String) -> Unit = { status.text = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_settings)
        status = findViewById(R.id.settingsStatus)
        diagnostics = findViewById(R.id.diagnosticsText)
        findViewById<Button>(R.id.choosePhoneButton).setOnClickListener { showPhonePickerDialog() }
        findViewById<Button>(R.id.homeAppButton).setOnClickListener {
            Toast.makeText(this, "The One-opstart wordt geforceerd…", Toast.LENGTH_SHORT).show()
            Thread {
                val result = LauncherControl.tryForceHomeWithRoot(this)
                runOnUiThread {
                    val message = when {
                        result.homeSet -> "✅ The One is via root als HOME ingesteld"
                        result.rootAvailable -> "⚠️ Root aanwezig, maar HOME kon niet worden gewijzigd. Boot/wake-force blijft actief."
                        else -> "✅ Boot/wake-force staat actief. Deze radio geeft gewone apps geen stille HOME-wijziging."
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    forceStartupNow()
                }
            }.start()
        }
        findViewById<Button>(R.id.contactsButton).setOnClickListener {
            startActivity(Intent(this, AllowedContactsActivity::class.java))
        }
        findViewById<Button>(R.id.tilesButton).setOnClickListener {
            startActivity(Intent(this, CarTileManagerActivity::class.java))
        }
        findViewById<Button>(R.id.diagnosticsButton).setOnClickListener {
            BluetoothListenerService.forcePing()
            diagnostics.text = BluetoothListenerService.diagnostics()
        }
        findViewById<Button>(R.id.clearAllButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Alles wissen?")
                .setMessage("Dit wist lokale WhatsApp-chatgeschiedenis, meldingen, tijdelijke spraakbestanden en diagnosegeschiedenis op de autoradio. Contacten, tegels, radiozenders en andere instellingen blijven staan.")
                .setPositiveButton("Alles wissen") { _, _ ->
                    CarSessionCleaner.clearAllEphemeral(this)
                    Toast.makeText(this, "Lokale ritgegevens gewist", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annuleren", null)
                .show()
        }
        MessageBus.addStatusListener(statusListener)
        diagnostics.text = BluetoothListenerService.diagnostics()
    }

    private fun forceStartupNow() {
        try {
            val intent = Intent(this, BluetoothListenerService::class.java).apply {
                action = BluetoothListenerService.ACTION_FORCE_STARTUP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, intent)
            else startService(intent)
        } catch (_: Exception) {}
    }

    private fun showPhonePickerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Geef The One eerst Bluetooth-toestemming", Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Koppel je telefoon eerst in de Bluetooth-instellingen van de radio", Toast.LENGTH_LONG).show()
            return
        }
        val labels = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kies je telefoon")
            .setItems(labels) { _, which ->
                val device = devices[which]
                PairedPhoneStore.setSelected(this, device.address, device.name ?: device.address)
                Toast.makeText(this, "Gekozen: ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
                stopService(Intent(this, BluetoothListenerService::class.java))
                val serviceIntent = Intent(this, BluetoothListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, serviceIntent)
                else startService(serviceIntent)
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    override fun onDestroy() {
        MessageBus.removeStatusListener(statusListener)
        super.onDestroy()
    }
}
