package com.gmailorg.carradio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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

        findViewById<View>(R.id.replyButton).setOnClickListener {
            Thread {
                val sent = BluetoothListenerService.requestVoiceReply()
                if (!sent) {
                    MessageBus.postMessage("⚠️ Geen verbinding met je telefoon — kan geen antwoord vragen.")
                }
            }.start()
        }

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
