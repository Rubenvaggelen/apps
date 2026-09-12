package com.gmailorg.carradio

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI voor deze specifieke headunit. Spraak wordt rechtstreeks opgenomen, niet via SpeechRecognizer. */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var messageContainer: LinearLayout
    private lateinit var replyButton: Button
    private val voiceRecorder = RadioVoiceRecorder()

    private val messageListener: (String) -> Unit = { text -> addMessage(text) }
    private val statusListener: (String) -> Unit = { text -> statusText.text = text }

    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startService() }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecording()
        else MessageBus.postMessage("⚠️ Microfoontoestemming is nodig om in de auto te antwoorden.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        messageContainer = findViewById(R.id.messageContainer)
        replyButton = findViewById(R.id.replyButton)

        MessageBus.addListener(messageListener)
        MessageBus.addStatusListener(statusListener)

        findViewById<View>(R.id.choosePhoneButton).setOnClickListener { showPhonePickerDialog() }
        replyButton.setOnClickListener {
            if (voiceRecorder.isRecording()) {
                voiceRecorder.stop()
                MessageBus.postStatus("Opname stoppen • audio voorbereiden...")
            } else {
                startVoiceReply()
            }
        }
        findViewById<View>(R.id.diagnosticsButton).setOnClickListener {
            val ping = BluetoothListenerService.forcePing()
            MessageBus.postMessage("🛠 ${BluetoothListenerService.diagnostics()}")
            if (!ping) MessageBus.postMessage("⚠️ Diagnose: geen actieve The One-dataverbinding.")
        }

        UpdateChecker.checkForUpdate(this)
        ensurePermissionThenStart()
    }

    private fun ensurePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        startService()
    }

    private fun startService() {
        val intent = Intent(this, BluetoothListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, intent) else applicationContext.startService(intent)
    }

    private fun startVoiceReply() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceRecording()
    }

    private fun startVoiceRecording() {
        if (!BluetoothListenerService.forcePing()) {
            MessageBus.postMessage("⚠️ Geen live verbinding met je telefoon. Wacht tot de status groen is.")
            return
        }
        MessageBus.postMessage("🎙️ Spreek nu je antwoord in. De opname stopt vanzelf na stilte.")
        MessageBus.postStatus("🎙️ Luisteren via de microfoon van deze autoradio...")
        replyButton.text = "⏹ Stop opname"

        voiceRecorder.start(onComplete = { result ->
            runOnUiThread { replyButton.text = "🎤 Antwoord inspreken" }
            when (result) {
                is RadioVoiceRecorder.Result.Success -> {
                    MessageBus.postStatus("Audio naar telefoon sturen...")
                    val sent = BluetoothListenerService.sendVoiceAudio(result.wavBytes)
                    if (sent) {
                        MessageBus.postMessage("📤 Audio verzonden (${result.durationMs / 1000.0}s) • telefoon maakt er tekst van...")
                    } else {
                        MessageBus.postMessage("⚠️ Audio opgenomen, maar verbinding viel weg. Probeer opnieuw.")
                    }
                }
                is RadioVoiceRecorder.Result.Error -> {
                    MessageBus.postMessage("⚠️ Microfoonopname mislukt: ${result.message}")
                    MessageBus.postStatus(BluetoothListenerService.diagnostics())
                }
            }
        })
    }

    private fun showPhonePickerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(this, "Geef eerst Bluetooth-toestemming.", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Geen gekoppelde apparaten gevonden. Koppel eerst je telefoon via Bluetooth.", Toast.LENGTH_LONG).show()
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
                startService()
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
        voiceRecorder.stop()
        MessageBus.removeListener(messageListener)
        MessageBus.removeStatusListener(statusListener)
        super.onDestroy()
    }
}
