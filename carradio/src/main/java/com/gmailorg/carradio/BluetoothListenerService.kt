package com.gmailorg.carradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Bluetooth-client voor de K2401 headunit.
 * Gebruikt primair het vaste RFCOMM-kanaal 8 dat op deze radio betrouwbaar bleek;
 * UUID/SDP blijft alleen fallback. Verzorgt WhatsApp, contactfilter en spraak/audio.
 */
class BluetoothListenerService : Service() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
        private const val CHANNEL_ID = "car_radio_service"
        private const val NOTIFICATION_ID = 1
        private const val VOICE_CHUNK_BYTES = 1800
        private const val FIXED_RFCOMM_CHANNEL = 8

        const val ACTION_FORCE_STARTUP = "com.gmailorg.carradio.action.FORCE_STARTUP"
        const val ACTION_CANCEL_STARTUP = "com.gmailorg.carradio.action.CANCEL_STARTUP"

        private val writeLock = Any()
        @Volatile private var activeWriter: BufferedWriter? = null
        @Volatile private var activeSocket: BluetoothSocket? = null
        @Volatile private var socketConnected = false
        @Volatile private var protocolVerified = false
        @Volatile private var lastRxAt = 0L
        @Volatile private var lastPongAt = 0L
        @Volatile private var selectedPhone = "-"
        @Volatile private var transport = "K2401-CH8"
        @Volatile private var lastProtocolLine = "-"

        fun isLive(): Boolean = socketConnected

        fun diagnostics(): String {
            val age = if (lastRxAt == 0L) "nooit" else "${((System.currentTimeMillis() - lastRxAt) / 1000)}s geleden"
            return "The One Car • telefoon=$selectedPhone • transport=$transport • verbinding=${if (socketConnected) "OK" else "UIT"} • app=${if (protocolVerified) "OK" else if (socketConnected) "ACTIEF" else "UIT"} • data=$age • rx=$lastProtocolLine"
        }

        fun forcePing(): Boolean = writeLine("SYS:PING:${System.currentTimeMillis()}")
        fun requestContacts(): Boolean = writeLine("CONTACTS_REQUEST")
        fun setContactFilterEnabled(enabled: Boolean): Boolean =
            writeLine("CONTACT_FILTER:${if (enabled) 1 else 0}")

        fun setContactAllowed(name: String, allowed: Boolean): Boolean =
            writeLine("CONTACT_ALLOW:${if (allowed) 1 else 0}:${enc(name)}")

        fun sendTextReply(conversation: String, text: String): Boolean {
            if (conversation.isBlank() || text.isBlank()) return false
            return writeLine("REPLY_TEXT_TO:${enc(conversation)}:${enc(text)}")
        }

        fun requestPhoneVoiceReply(conversation: String): Boolean =
            writeLine("REPLY_REQUEST_TO:${enc(conversation)}")

        fun sendVoiceAudio(conversation: String, wavBytes: ByteArray): Boolean {
            if (conversation.isBlank() || wavBytes.isEmpty() || wavBytes.size > 1_500_000) return false
            val id = System.currentTimeMillis().toString(36)
            synchronized(writeLock) {
                val writer = activeWriter ?: return false
                return try {
                    writer.write("VOICE_BEGIN:$id:audio/wav:${wavBytes.size}:${enc(conversation)}")
                    writer.newLine()
                    var offset = 0
                    while (offset < wavBytes.size) {
                        val len = minOf(VOICE_CHUNK_BYTES, wavBytes.size - offset)
                        val chunk = Base64.encodeToString(wavBytes, offset, len, Base64.NO_WRAP)
                        writer.write("VOICE_CHUNK:$id:$chunk")
                        writer.newLine()
                        offset += len
                    }
                    writer.write("VOICE_END:$id")
                    writer.newLine()
                    writer.flush()
                    true
                } catch (_: Exception) {
                    closeActiveConnection()
                    false
                }
            }
        }

        private fun enc(text: String): String =
            Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        private fun writeLine(line: String): Boolean {
            synchronized(writeLock) {
                val writer = activeWriter ?: return false
                return try {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                    true
                } catch (_: Exception) {
                    closeActiveConnection()
                    false
                }
            }
        }

        private fun closeActiveConnection() {
            synchronized(writeLock) {
                activeWriter = null
                socketConnected = false
                protocolVerified = false
                try { activeSocket?.close() } catch (_: Exception) {}
                activeSocket = null
            }
        }
    }

    @Volatile private var running = false
    private var connectorThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var startupGeneration = 0L
    @Volatile private var lastWakeEnforceAt = 0L

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_POWER_CONNECTED) {
                val now = System.currentTimeMillis()
                if (now - lastWakeEnforceAt > 10_000L) {
                    lastWakeEnforceAt = now
                    enforceStartup(intArrayOf(900, 2800, 6500))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        registerWakeReceiver()
        startConnecting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) startConnecting()
        when (intent?.action) {
            ACTION_FORCE_STARTUP -> enforceStartup(intArrayOf(0, 1500, 3500, 7000, 12000, 20000, 30000))
            ACTION_CANCEL_STARTUP -> startupGeneration++
        }
        return START_STICKY
    }

    private fun registerWakeReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(wakeReceiver, filter)
        } catch (_: Exception) {}
    }

    /**
     * K2401-specifieke launcher guard. De fabriekslauncher wordt op deze radio
     * vaak pas na BOOT_COMPLETED gestart. Meerdere expliciete starts zorgen dat
     * The One uiteindelijk bovenop eindigt. Een echte gebruikersactie in
     * MainActivity annuleert de resterende starts zodat Maps/Radio/etc. normaal
     * gebruikt kunnen worden.
     */
    private fun enforceStartup(delaysMs: IntArray) {
        val generation = ++startupGeneration
        for (delay in delaysMs) {
            mainHandler.postDelayed({
                if (!running || generation != startupGeneration) return@postDelayed
                try {
                    val launch = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("forced_startup", true)
                    }
                    startActivity(launch)
                } catch (_: Exception) {}
            }, delay.toLong())
        }
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "The One Car-koppeling", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The One Car")
            .setContentText("Automatisch verbinden met je telefoon...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startConnecting() {
        if (running) return
        running = true
        connectorThread = thread(name = "TheOne-CarReconnect") {
            while (running) {
                val address = PairedPhoneStore.selectedAddress(this)
                if (address == null) {
                    MessageBus.postStatus("⚠️ Kies bij Instellingen één keer je telefoon")
                    sleepQuietly(2500)
                    continue
                }
                selectedPhone = PairedPhoneStore.selectedName(this) ?: address
                connectSession(address)
                if (running) sleepQuietly(1200)
            }
        }
    }

    private fun connectSession(address: String) {
        var socket: BluetoothSocket? = null
        var heartbeatRunning = false
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            MessageBus.postStatus("Verbinden met $selectedPhone...")
            try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
            val device = adapter.getRemoteDevice(address)

            // K2401-specifiek: de vaste RFCOMM-route op kanaal 8 is op deze
            // headunit aantoonbaar betrouwbaarder dan SDP/UUID. UUID blijft alleen fallback.
            var fixedFailure: Exception? = null
            val fixedSocket = createFixedChannelSocket(device)
            if (fixedSocket != null) {
                try {
                    transport = "K2401-CH8"
                    fixedSocket.connect()
                    socket = fixedSocket
                } catch (e: Exception) {
                    fixedFailure = e
                    try { fixedSocket.close() } catch (_: Exception) {}
                }
            }
            if (socket == null) {
                transport = "UUID-FALLBACK"
                val fallbackSocket = device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
                try {
                    fallbackSocket.connect()
                    socket = fallbackSocket
                } catch (e: Exception) {
                    try { fallbackSocket.close() } catch (_: Exception) {}
                    throw fixedFailure ?: e
                }
            }

            val connectedSocket = socket ?: throw IOException("Geen bruikbare RFCOMM-socket")
            val writer = BufferedWriter(OutputStreamWriter(connectedSocket.outputStream, Charsets.UTF_8))
            synchronized(writeLock) {
                activeSocket = connectedSocket
                activeWriter = writer
                socketConnected = true
                protocolVerified = false
                lastRxAt = 0L
                lastPongAt = 0L
                lastProtocolLine = "-"
            }
            MessageBus.postStatus("✅ Verbonden met $selectedPhone • $transport")

            // Heartbeat is alleen diagnostiek. Op de K2401 sluiten we een werkende
            // RFCOMM-link NIET meer af alleen omdat PONG/HELLO uitblijft.
            heartbeatRunning = true
            val thisSocket = connectedSocket
            thread(name = "TheOne-CarHeartbeat") {
                while (running && heartbeatRunning && activeSocket === thisSocket) {
                    writeLine("SYS:PING:${System.currentTimeMillis()}")
                    sleepQuietly(5000)
                }
            }

            val reader = BufferedReader(InputStreamReader(connectedSocket.inputStream, Charsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                lastRxAt = System.currentTimeMillis()
                lastProtocolLine = line.take(52)
                handleLine(line)
            }
        } catch (_: SecurityException) {
            MessageBus.postStatus("⚠️ Geen Bluetooth-toestemming voor The One Car")
            sleepQuietly(2500)
        } catch (_: Exception) {
            MessageBus.postStatus("Verbinding weg • automatisch opnieuw proberen...")
        } finally {
            heartbeatRunning = false
            synchronized(writeLock) {
                if (activeSocket === socket) {
                    activeWriter = null
                    activeSocket = null
                    socketConnected = false
                    protocolVerified = false
                }
            }
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("SYS:HELLO:") -> {
                protocolVerified = true
                lastPongAt = System.currentTimeMillis()
                MessageBus.postStatus("✅ Verbonden met $selectedPhone")
                requestContacts()
            }
            line.startsWith("SYS:PONG:") -> {
                protocolVerified = true
                lastPongAt = System.currentTimeMillis()
                MessageBus.postStatus("✅ Verbonden met $selectedPhone")
            }
            line.startsWith("WA_MSG:") -> {
                val parts = line.split(":", limit = 4)
                if (parts.size == 4) {
                    val contact = dec(parts[1])
                    val text = dec(parts[2])
                    val time = parts[3].toLongOrNull() ?: System.currentTimeMillis()
                    if (contact.isNotBlank() && text.isNotBlank()) {
                        ConversationStore.addIncoming(this, contact, text, time)
                        RadioContactStore.registerKnown(this, contact)
                        MessageBus.postMessage("$contact: $text")
                        MessageBus.postDataChanged()
                    }
                }
            }
            line.startsWith("WA_SENT:") -> {
                val parts = line.split(":", limit = 4)
                if (parts.size == 4) {
                    val contact = dec(parts[1])
                    val text = dec(parts[2])
                    val time = parts[3].toLongOrNull() ?: System.currentTimeMillis()
                    if (contact.isNotBlank() && text.isNotBlank()) {
                        ConversationStore.addOutgoing(this, contact, text, time)
                        MessageBus.postDataChanged()
                    }
                }
            }
            line.startsWith("CONTACT_STATE:") -> {
                val parts = line.split(":", limit = 4)
                if (parts.size == 4) {
                    RadioContactStore.setFilterEnabled(this, parts[1] == "1")
                    val allowed = parts[2] == "1"
                    val name = dec(parts[3])
                    if (name.isNotBlank()) RadioContactStore.putContact(this, name, allowed)
                    MessageBus.postDataChanged()
                }
            }
            line.startsWith("CONTACTS_BEGIN:") -> {
                val enabled = line.removePrefix("CONTACTS_BEGIN:") == "1"
                RadioContactStore.beginSync(this, enabled)
            }
            line.startsWith("CONTACT:") -> {
                val parts = line.split(":", limit = 3)
                if (parts.size == 3) {
                    val allowed = parts[1] == "1"
                    val name = dec(parts[2])
                    if (name.isNotBlank()) RadioContactStore.putContact(this, name, allowed)
                }
            }
            line == "CONTACTS_END" -> MessageBus.postDataChanged()
            line.startsWith("MSG:") -> {
                val text = dec(line.removePrefix("MSG:"))
                if (text.isNotBlank()) MessageBus.postMessage(text)
            }
            line.startsWith("STATUS:") -> MessageBus.postMessage("✅ " + line.removePrefix("STATUS:"))
            else -> MessageBus.postMessage(line)
        }
    }

    private fun dec(value: String): String = try {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    private fun createFixedChannelSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod(
                "createInsecureRfcommSocket", Int::class.javaPrimitiveType
            )
            method.invoke(device, FIXED_RFCOMM_CHANNEL) as? BluetoothSocket
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        running = false
        startupGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
        closeActiveConnection()
        connectorThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
