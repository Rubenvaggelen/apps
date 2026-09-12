package com.gmailorg.carradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Verbindingsservice voor de K2401 headunit.
 *
 * Volgorde is bewust:
 * 1) Wi-Fi/LAN wanneer radio en telefoon op hetzelfde netwerk/hotspot zitten.
 * 2) Vast RFCOMM-kanaal 8 als noodfallback.
 *
 * BT-UUID is bewust uitgeschakeld op deze K2401: in praktijktests pakte de
 * headunit daarmee geregeld een ogenschijnlijk open socket zonder betrouwbaar
 * The One-protocol. Wi-Fi is dus de voorkeursroute; CH8 is alleen fallback.
 */
class BluetoothListenerService : Service() {

    companion object {
        val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
        private const val CHANNEL_ID = "car_radio_service"
        private const val NOTIFICATION_ID = 1
        private const val FIXED_RFCOMM_CHANNEL = 8
        private const val WIFI_DISCOVERY_PORT = 38472
        private const val WIFI_DISCOVER = "THE_ONE_DISCOVER_V1"
        private const val WIFI_REPLY_PREFIX = "THE_ONE_HERE:"
        private const val WIFI_AUTH = "the-one-k2401-8ab8c3d0-v1"

        const val ACTION_FORCE_STARTUP = "com.gmailorg.carradio.action.FORCE_STARTUP"
        const val ACTION_CANCEL_STARTUP = "com.gmailorg.carradio.action.CANCEL_STARTUP"

        private val writeLock = Any()
        @Volatile private var activeWriter: BufferedWriter? = null
        @Volatile private var activeConnection: Closeable? = null
        @Volatile private var socketConnected = false
        @Volatile private var protocolVerified = false
        @Volatile private var lastRxAt = 0L
        @Volatile private var lastPongAt = 0L
        @Volatile private var selectedPhone = "-"
        @Volatile private var transport = "-"
        @Volatile private var lastProtocolLine = "-"

        fun isLive(): Boolean = socketConnected && activeWriter != null

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

        fun removeContact(name: String): Boolean =
            writeLine("CONTACT_REMOVE:${enc(name)}")

        fun sendTextReply(conversation: String, text: String): Boolean {
            if (conversation.isBlank() || text.isBlank()) return false
            return writeLine("REPLY_TEXT_TO:${enc(conversation)}:${enc(text)}")
        }

        fun requestPhoneVoiceReply(conversation: String): Boolean =
            writeLine("REPLY_REQUEST_TO:${enc(conversation)}")

        fun requestHousehold(): Boolean = writeLine("HOUSEHOLD_REQUEST")
        fun householdAdd(text: String): Boolean = writeLine("HOUSEHOLD_ADD:${enc(text)}")
        fun householdToggle(id: String): Boolean = writeLine("HOUSEHOLD_TOGGLE:${enc(id)}")
        fun householdRemove(id: String): Boolean = writeLine("HOUSEHOLD_REMOVE:${enc(id)}")
        fun householdClearDone(): Boolean = writeLine("HOUSEHOLD_CLEAR_DONE")
        fun setHouseholdAlert(enabled: Boolean): Boolean = writeLine("HOUSEHOLD_ALERT_SET:${if (enabled) 1 else 0}")

        fun requestParking(): Boolean = writeLine("PARKING_REQUEST")
        fun parkingAdd(address: String): Boolean = writeLine("PARKING_ADD:${enc(address)}")
        fun parkingRemove(id: String): Boolean = writeLine("PARKING_REMOVE:${enc(id)}")
        fun parkingSetTimer(epochMillis: Long): Boolean = writeLine("PARKING_TIMER_SET:$epochMillis")
        fun parkingClearTimer(): Boolean = writeLine("PARKING_TIMER_CLEAR")

        fun requestVoiceNote(conversation: String): Boolean = writeLine("VOICE_NOTE_REQUEST:${enc(conversation)}")

        fun sendVoiceAudio(conversation: String, wavBytes: ByteArray): Boolean {
            if (conversation.isBlank() || wavBytes.isEmpty() || wavBytes.size > 2_200_000) return false
            val id = System.currentTimeMillis().toString(36)
            val chunkBytes = if (transport == "WIFI-LAN") 24_000 else 8_000
            synchronized(writeLock) {
                val writer = activeWriter ?: return false
                return try {
                    writer.write("VOICE_BEGIN:$id:audio/wav:${wavBytes.size}:${enc(conversation)}")
                    writer.newLine()
                    var offset = 0
                    while (offset < wavBytes.size) {
                        val len = minOf(chunkBytes, wavBytes.size - offset)
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
                try { activeConnection?.close() } catch (_: Exception) {}
                activeConnection = null
            }
        }
    }

    @Volatile private var running = false
    private var connectorThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var startupGeneration = 0L
    @Volatile private var lastWakeEnforceAt = 0L
    private var incomingMediaId: String? = null
    private var incomingMediaContact: String? = null
    private var incomingMediaMime: String? = null
    private var incomingMediaBuffer: ByteArrayOutputStream? = null
    private var receivedMediaPlayer: MediaPlayer? = null

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
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED && transport != "WIFI-LAN") {
                // Forceer een nieuwe poging wanneer de gebruiker Bluetooth aan/uit zet.
                closeActiveConnection()
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
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(wakeReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun enforceStartup(delaysMs: IntArray) {
        val generation = ++startupGeneration
        for (delay in delaysMs) {
            mainHandler.postDelayed({
                if (!running || generation != startupGeneration) return@postDelayed
                try {
                    val launch = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
            .setContentText("Verbinden via Wi-Fi of Bluetooth...")
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
                selectedPhone = PairedPhoneStore.selectedName(this) ?: address ?: "telefoon"
                connectSession(address)
                if (running) sleepQuietly(900)
            }
        }
    }

    private data class Connection(
        val closeable: Closeable,
        val reader: BufferedReader,
        val writer: BufferedWriter,
        val name: String
    )

    private fun connectSession(address: String?) {
        var connection: Connection? = null
        var heartbeatRunning = false
        try {
            MessageBus.postStatus("Verbinden met $selectedPhone...")

            // Wi-Fi eerst: dit blijft werken als de K2401-fabrieks-Bluetoothapp kanaal 8 inpikt.
            connection = tryWifiConnection()

            if (connection == null && !address.isNullOrBlank()) {
                connection = tryFixedChannelBluetooth(address)
            }

            if (connection == null) {
                if (address.isNullOrBlank()) {
                    MessageBus.postStatus("⚠️ Geen Wi-Fi-link gevonden; kies bij Instellingen je telefoon voor Bluetooth fallback")
                } else {
                    MessageBus.postStatus("Geen link • Wi-Fi/CH8 opnieuw proberen...")
                }
                return
            }

            transport = connection.name
            synchronized(writeLock) {
                activeConnection = connection.closeable
                activeWriter = connection.writer
                socketConnected = true
                protocolVerified = false
                lastRxAt = 0L
                lastPongAt = 0L
                lastProtocolLine = "-"
            }
            MessageBus.postStatus("✅ Verbonden met $selectedPhone • $transport")

            heartbeatRunning = true
            val thisConnection = connection.closeable
            thread(name = "TheOne-CarHeartbeat") {
                var checks = 0
                while (running && heartbeatRunning && activeConnection === thisConnection) {
                    writeLine("SYS:PING:${System.currentTimeMillis()}")
                    sleepQuietly(5000)
                    checks++
                    // CH8 is alleen noodfallback. Zodra Wi-Fi later tijdens de rit
                    // beschikbaar wordt, verbreken we de fallback zodat de volgende
                    // reconnect automatisch via Wi-Fi/LAN loopt.
                    if (connection.name != "WIFI-LAN" && checks % 2 == 0) {
                        if (discoverPhoneOnWifi() != null) {
                            try { thisConnection.close() } catch (_: Exception) {}
                            break
                        }
                    }
                }
            }

            while (running && activeConnection === connection.closeable) {
                val line = connection.reader.readLine() ?: break
                if (line.isBlank()) continue
                lastRxAt = System.currentTimeMillis()
                lastProtocolLine = line.take(52)
                handleLine(line)
            }
        } catch (_: SecurityException) {
            MessageBus.postStatus("⚠️ Geen Bluetooth-toestemming voor fallback")
            sleepQuietly(1500)
        } catch (_: Exception) {
            MessageBus.postStatus("Verbinding weg • automatisch opnieuw proberen...")
        } finally {
            heartbeatRunning = false
            synchronized(writeLock) {
                if (connection != null && activeConnection === connection.closeable) {
                    activeWriter = null
                    activeConnection = null
                    socketConnected = false
                    protocolVerified = false
                }
            }
            try { connection?.closeable?.close() } catch (_: Exception) {}
        }
    }

    private fun tryWifiConnection(): Connection? {
        val target = discoverPhoneOnWifi() ?: return null
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(target.first, target.second), 1400)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            writer.write("AUTH:$WIFI_AUTH")
            writer.newLine()
            writer.flush()
            Connection(socket, reader, writer, "WIFI-LAN")
        } catch (_: Exception) {
            null
        }
    }

    /** Zoek de telefoon via een kleine UDP-discovery op hotspot/LAN. */
    private fun discoverPhoneOnWifi(): Pair<InetAddress, Int>? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 900
            }
            val bytes = WIFI_DISCOVER.toByteArray(Charsets.UTF_8)
            val destinations = linkedSetOf<InetAddress>()
            try { destinations.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
            gatewayAddress()?.let { destinations.add(it) }

            destinations.forEach { address ->
                try {
                    socket.send(DatagramPacket(bytes, bytes.size, address, WIFI_DISCOVERY_PORT))
                } catch (_: Exception) {}
            }

            val buffer = ByteArray(128)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val reply = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
            if (!reply.startsWith(WIFI_REPLY_PREFIX)) return null
            val port = reply.removePrefix(WIFI_REPLY_PREFIX).toIntOrNull() ?: return null
            packet.address to port
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun gatewayAddress(): InetAddress? {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val gateway = wifi.dhcpInfo?.gateway ?: 0
            if (gateway == 0) return null
            val bytes = byteArrayOf(
                (gateway and 0xff).toByte(),
                (gateway shr 8 and 0xff).toByte(),
                (gateway shr 16 and 0xff).toByte(),
                (gateway shr 24 and 0xff).toByte()
            )
            InetAddress.getByAddress(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryUuidBluetooth(address: String): Connection? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!adapter.isEnabled) return null
            try { adapter.cancelDiscovery() } catch (_: Exception) {}
            val device = adapter.getRemoteDevice(address)
            val socket = device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
            socket.connect()
            Connection(
                socket,
                BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)),
                BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8)),
                "BT-UUID"
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun tryFixedChannelBluetooth(address: String): Connection? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!adapter.isEnabled) return null
            try { adapter.cancelDiscovery() } catch (_: Exception) {}
            val device = adapter.getRemoteDevice(address)
            val socket = createFixedChannelSocket(device) ?: return null
            socket.connect()
            Connection(
                socket,
                BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)),
                BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8)),
                "BT-CH8-FALLBACK"
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("SYS:HELLO:") -> {
                protocolVerified = true
                lastPongAt = System.currentTimeMillis()
                MessageBus.postStatus("✅ Verbonden met $selectedPhone • $transport")
                requestContacts(); requestHousehold(); requestParking()
            }
            line.startsWith("SYS:PONG:") -> {
                protocolVerified = true
                lastPongAt = System.currentTimeMillis()
                MessageBus.postStatus("✅ Verbonden met $selectedPhone • $transport")
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
                        MessageBus.postMessage("${ContactAliases.displayName(contact)}: $text")
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
                RadioContactStore.beginSync(this, line.removePrefix("CONTACTS_BEGIN:") == "1")
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
            line == "HOUSEHOLD_BEGIN" -> HouseholdMirrorStore.beginSync()
            line.startsWith("HOUSEHOLD_ITEM:") -> {
                val parts = line.split(":", limit = 4)
                if (parts.size == 4) {
                    val id = dec(parts[1]); val done = parts[2] == "1"; val text = dec(parts[3])
                    if (id.isNotBlank() && text.isNotBlank()) HouseholdMirrorStore.stage(CarShoppingItem(id, text, done))
                }
            }
            line.startsWith("HOUSEHOLD_ALERT_STATE:") -> HouseholdMirrorStore.setAlertEnabled(this, line.removePrefix("HOUSEHOLD_ALERT_STATE:") == "1")
            line == "HOUSEHOLD_END" -> { HouseholdMirrorStore.finishSync(this); MessageBus.postDataChanged() }
            line == "PARKING_BEGIN" -> ParkingMirrorStore.beginSync()
            line.startsWith("PARKING_ITEM:") -> {
                val parts = line.split(":", limit = 3)
                if (parts.size == 3) {
                    val id = dec(parts[1]); val address = dec(parts[2])
                    if (id.isNotBlank() && address.isNotBlank()) ParkingMirrorStore.stage(CarParkingAddress(id, address))
                }
            }
            line.startsWith("PARKING_TIMER:") -> ParkingMirrorStore.setTimer(this, line.removePrefix("PARKING_TIMER:").toLongOrNull())
            line == "PARKING_END" -> { ParkingMirrorStore.finishSync(this); MessageBus.postDataChanged() }
            line.startsWith("SUPERMARKET_ALERT:") -> {
                val text = dec(line.removePrefix("SUPERMARKET_ALERT:"))
                if (text.isNotBlank()) showSupermarketAlert(text)
            }
            line.startsWith("VOICE_NOTE_STATUS:") -> {
                val text = dec(line.removePrefix("VOICE_NOTE_STATUS:"))
                if (text.isNotBlank()) MessageBus.postStatus(text)
            }
            line.startsWith("MEDIA_BEGIN:") -> beginIncomingMedia(line)
            line.startsWith("MEDIA_CHUNK:") -> appendIncomingMedia(line)
            line.startsWith("MEDIA_END:") -> finishIncomingMedia(line)
            line.startsWith("MSG:") -> {
                val text = dec(line.removePrefix("MSG:"))
                if (text.isNotBlank()) MessageBus.postMessage(text)
            }
            line.startsWith("STATUS:") -> MessageBus.postMessage("✅ " + line.removePrefix("STATUS:"))
            else -> MessageBus.postMessage(line)
        }
    }

    private fun showSupermarketAlert(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "the_one_car_supermarket"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Supermarkt-herinneringen", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            this, 44, Intent(this, HouseholdCarActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Je bent bij een supermarkt 🛒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(4201, n)
        MessageBus.postMessage("🛒 $text")
    }

    private fun beginIncomingMedia(line: String) {
        val parts = line.split(":", limit = 5)
        if (parts.size != 5) return
        val expected = parts[3].toIntOrNull()?.coerceAtMost(12_000_000) ?: return
        incomingMediaId = parts[1]
        incomingMediaMime = dec(parts[2])
        incomingMediaContact = dec(parts[4])
        incomingMediaBuffer = ByteArrayOutputStream(expected.coerceAtLeast(32_000))
    }

    private fun appendIncomingMedia(line: String) {
        val parts = line.split(":", limit = 3)
        if (parts.size != 3 || parts[1] != incomingMediaId) return
        val decoded = try { Base64.decode(parts[2], Base64.DEFAULT) } catch (_: Exception) { return }
        val out = incomingMediaBuffer ?: return
        if (out.size() + decoded.size <= 12_000_000) out.write(decoded)
    }

    private fun finishIncomingMedia(line: String) {
        if (line.removePrefix("MEDIA_END:") != incomingMediaId) return
        val bytes = incomingMediaBuffer?.toByteArray() ?: ByteArray(0)
        val contact = incomingMediaContact.orEmpty()
        val mime = incomingMediaMime.orEmpty()
        incomingMediaId = null; incomingMediaBuffer = null; incomingMediaContact = null; incomingMediaMime = null
        if (bytes.isEmpty() || contact.isBlank()) return
        try {
            val ext = when {
                mime.contains("ogg", true) || mime.contains("opus", true) -> ".ogg"
                mime.contains("mpeg", true) || mime.contains("mp3", true) -> ".mp3"
                mime.contains("mp4", true) || mime.contains("m4a", true) -> ".m4a"
                mime.contains("wav", true) -> ".wav"
                else -> ".audio"
            }
            val file = File(cacheDir, "wa_voice_${System.currentTimeMillis()}$ext")
            FileOutputStream(file).use { it.write(bytes) }
            ConversationStore.attachLatestVoiceMedia(this, contact, file.absolutePath)
            MessageBus.postDataChanged()
            playReceivedVoice(file.absolutePath)
        } catch (_: Exception) {
            MessageBus.postStatus("Spraakbericht kon niet worden opgeslagen")
        }
    }

    private fun playReceivedVoice(path: String) {
        try { receivedMediaPlayer?.release() } catch (_: Exception) {}
        receivedMediaPlayer = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(path)
                setOnPreparedListener { it.start(); MessageBus.postStatus("▶ Spraakbericht wordt afgespeeld") }
                setOnCompletionListener { MessageBus.postStatus("✅ Spraakbericht afgespeeld") }
                prepareAsync()
            }
        } catch (_: Exception) { null }
    }

    private fun dec(value: String): String = try {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    private fun createFixedChannelSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
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
        try { receivedMediaPlayer?.release() } catch (_: Exception) {}
        connectorThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
