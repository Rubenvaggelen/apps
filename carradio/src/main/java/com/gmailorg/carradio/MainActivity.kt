package com.gmailorg.carradio

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var messageContainer: LinearLayout

    private val messageListener: (String) -> Unit = { text -> addMessage(text) }
    private val statusListener: (String) -> Unit = { text -> statusText.text = text }

    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        messageContainer = findViewById(R.id.messageContainer)

        MessageBus.addListener(messageListener)
        MessageBus.addStatusListener(statusListener)

        findViewById<View>(R.id.choosePhoneButton).setOnClickListener {
            showPhonePickerDialog()
        }

        findViewById<View>(R.id.replyButton).setOnClickListener {
            Thread {
                val sent = BluetoothListenerService.requestVoiceReply()
                if (!sent) {
                    MessageBus.postMessage("⚠️ Geen verbinding met je telefoon — kan geen antwoord vragen.")
                }
            }.start()
        }

        UpdateChecker.checkForUpdate(this)

        ensurePermissionThenStart()
    }

    private fun ensurePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        startService()
    }

    private fun startService() {
        startForegroundService(Intent(this, BluetoothListenerService::class.java))
    }

    /** Toont een lijst van al gekoppelde Bluetooth-apparaten, zodat de gebruiker kan aanwijzen welke de telefoon is. */
    private fun showPhonePickerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(this, "Geef eerst Bluetooth-toestemming.", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Geen gekoppelde apparaten gevonden. Koppel eerst je telefoon via Bluetooth-instellingen.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kies je telefoon")
            .setItems(labels) { _, which ->
                val device = devices[which]
                PairedPhoneStore.setSelected(this, device.address, device.name ?: device.address)
                Toast.makeText(this, "Gekozen: ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun addMessage(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val row = TextView(this).apply {
            this.text = "$time  •  $text"
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        messageContainer.addView(row, 0)
    }

    override fun onDestroy() {
        MessageBus.removeListener(messageListener)
        MessageBus.removeStatusListener(statusListener)
        super.onDestroy()
    }
}
