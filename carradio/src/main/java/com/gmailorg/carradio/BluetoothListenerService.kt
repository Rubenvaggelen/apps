package com.gmailorg.carradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Radio-specifieke Bluetooth-client voor deze headunit.
 * Reconnect zelf voortdurend, gebruikt keep-alive en markeert de verbinding pas
 * als de telefoon daadwerkelijk op protocoldata reageert.
 */
class BluetoothListenerService : Service() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
        private const val FIXED_RFCOMM_CHANNEL = 8
        private const val CHANNEL_ID = "car_radio_service"
        private const val NOTIFICATION_ID = 1
        private const val CMD_REPLY_REQUEST = "REPLY_REQUEST"
        private const val CMD_REPLY_TEXT_PREFIX = "REPLY_TEXT:"
        private const val VOICE_CHUNK_BYTES = 1800

        private val writeLock = Any()
        @Volatile private var activeWriter: BufferedWriter? = null
        @Volatile private var activeSocket: BluetoothSocket? = null
        @Volatile private var socketConnected = false
        @Volatile private var protocolVerified = false
        @Volatile private var lastRxAt = 0L
        @Volatile private var lastPongAt = 0L
        @Volatile private var selectedPhone = "-"

        fun requestVoiceReply(): Boolean = writeLine(CMD_REPLY_REQUEST)

        fun sendVoiceReply(text: String): Boolean {
            if (text.isBlank()) return false
            val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return writeLine("$CMD_REPLY_TEXT_PREFIX$encoded")
        }

        fun sendVoiceAudio(wavBytes: ByteArray): Boolean {
            if (wavBytes.isEmpty() || wavBytes.size > 1_500_000) return false
            val id = System.currentTimeMillis().toString(36)
            synchronized(writeLock) {
                val writer = activeWriter ?: return false
                return try {
                    writer.write("VOICE_BEGIN:$id:audio/wav:${wavBytes.size}")
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

        fun diagnostics(): String {
            val age = if (lastRxAt == 0L) "nooit" else "${((System.currentTimeMillis() - lastRxAt) / 1000)}s geleden"
            return "Radio V2 • telefoon=$selectedPhone • socket=${if (socketConnected) "OK" else "UIT"} • protocol=${if (protocolVerified) "OK" else "WACHT"} • laatste data=$age"
        }

        fun forcePing(): Boolean = writeLine("SYS:PING:${System.currentTimeMillis()}")

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

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        startConnecting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) startConnecting()
        return START_STICKY
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Autoradio-koppeling", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The One – Autoradio V2")
            .setContentText("Automatisch verbinden met je telefoon...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startConnecting() {
        if (running) return
        running = true
        connectorThread = thread(name = "TheOne-RadioReconnect") {
            while (running) {
                val address = PairedPhoneStore.selectedAddress(this)
                if (address == null) {
                    MessageBus.postStatus("⚠️ Kies één keer je telefoon. Daarna verbindt deze radio automatisch.")
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
            MessageBus.postStatus("Radio V2 • verbinden met $selectedPhone...")
            try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
            val device = adapter.getRemoteDevice(address)

            socket = createFixedChannelSocket(device) ?: device.createRfcommSocketToServiceRecord(APP_UUID)
            socket.connect()

            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
            synchronized(writeLock) {
                activeSocket = socket
                activeWriter = writer
                socketConnected = true
                protocolVerified = false
                lastRxAt = System.currentTimeMillis()
                lastPongAt = System.currentTimeMillis()
            }
            MessageBus.postStatus("Bluetooth-kanaal open • telefoon controleren...")

            heartbeatRunning = true
            val thisSocket = socket
            thread(name = "TheOne-RadioHeartbeat") {
                while (running && heartbeatRunning && activeSocket === thisSocket) {
                    val token = System.currentTimeMillis()
                    if (!writeLine("SYS:PING:$token")) break
                    sleepQuietly(4000)
                    if (protocolVerified && System.currentTimeMillis() - lastPongAt > 13_000L) {
                        MessageBus.postStatus("Telefoon reageert niet meer • opnieuw verbinden...")
                        try { thisSocket.close() } catch (_: Exception) {}
                        break
                    }
                }
            }

            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                lastRxAt = System.currentTimeMillis()
                when {
                    line.startsWith("SYS:HELLO:") -> {
                        protocolVerified = true
                        lastPongAt = System.currentTimeMillis()
                        MessageBus.postStatus("✅ Verbonden • live dataverbinding met telefoon")
                    }
                    line.startsWith("SYS:PONG:") -> {
                        protocolVerified = true
                        lastPongAt = System.currentTimeMillis()
                        MessageBus.postStatus("✅ Verbonden • live dataverbinding met telefoon")
                    }
                    line.startsWith("MSG:") -> {
                        val text = try {
                            String(Base64.decode(line.removePrefix("MSG:"), Base64.DEFAULT), Charsets.UTF_8)
                        } catch (_: Exception) { "" }
                        if (text.isNotBlank()) MessageBus.postMessage(text)
                    }
                    line.startsWith("STATUS:") -> MessageBus.postMessage("✅ " + line.removePrefix("STATUS:"))
                    else -> MessageBus.postMessage(line) // compatibiliteit met oudere telefoonversies
                }
            }
        } catch (e: SecurityException) {
            MessageBus.postStatus("⚠️ Geen Bluetooth-toestemming voor The One – Autoradio.")
            sleepQuietly(2500)
        } catch (e: Exception) {
            MessageBus.postStatus("Radio V2 • verbinding weg • automatisch opnieuw proberen...")
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

    override fun onDestroy() {
        running = false
        closeActiveConnection()
        connectorThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createFixedChannelSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, FIXED_RFCOMM_CHANNEL) as? BluetoothSocket
        } catch (_: Exception) { null }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
