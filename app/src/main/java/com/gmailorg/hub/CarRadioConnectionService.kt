package com.gmailorg.hub

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Telefoonkant van The One Car.
 *
 * De telefoon-UI blijft ongewijzigd. Deze service biedt meerdere transports:
 * Wi-Fi/LAN heeft voorrang en vast kanaal 8 is alleen noodfallback.
 * Bluetooth UUID is op deze K2401 bewust uitgeschakeld omdat die route in
 * praktijktests geregeld een socket opende die geen stabiel The One-protocol voerde.
 */
class CarRadioConnectionService : Service() {

    companion object {
        private val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
        private const val TAG = "CarRadioConnection"
        private const val CHANNEL_ID = "car_radio_connection"
        private const val NOTIFICATION_ID = 2
        private const val MAX_PENDING = 40
        private const val FIXED_RFCOMM_CHANNEL = 8
        private const val WIFI_TCP_PORT = 38471
        private const val WIFI_DISCOVERY_PORT = 38472
        private const val WIFI_DISCOVER = "THE_ONE_DISCOVER_V1"
        private const val WIFI_REPLY_PREFIX = "THE_ONE_HERE:"
        private const val WIFI_AUTH = "the-one-k2401-8ab8c3d0-v1"

        private val writeLock = Any()
        private val pendingNotifications = ArrayDeque<String>()
        private val connectionCounter = AtomicLong(0L)

        @Volatile private var activeWriter: BufferedWriter? = null
        @Volatile private var activeConnection: Closeable? = null
        @Volatile private var activeConnectionId = 0L
        @Volatile private var activePriority = 0
        @Volatile private var activeTransport = "-"

        fun start(context: Context) {
            val intent = Intent(context, CarRadioConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarRadioConnectionService::class.java))
        }

        fun isRadioConnected(): Boolean = activeWriter != null
        fun currentTransport(): String = activeTransport

        fun sendMessage(text: String): Boolean {
            val line = when {
                text.startsWith("STATUS:") || text.startsWith("SYS:") -> text
                else -> "MSG:" + enc(text)
            }
            return sendProtocolLine(line, line.startsWith("MSG:"))
        }

        fun sendWhatsAppMessage(title: String, text: String, postTime: Long): Boolean {
            return sendProtocolLine("WA_MSG:${enc(title)}:${enc(text)}:$postTime", true)
        }

        fun sendContactState(name: String, allowed: Boolean, filterEnabled: Boolean): Boolean {
            val line = "CONTACT_STATE:${if (filterEnabled) 1 else 0}:${if (allowed) 1 else 0}:${enc(name)}"
            return sendProtocolLine(line, false)
        }

        fun sendHouseholdSnapshot(context: Context): Boolean {
            ShoppingListStore.init(context.applicationContext)
            if (!isRadioConnected()) return false
            sendProtocolLine("HOUSEHOLD_BEGIN", false)
            ShoppingListStore.getAll().forEach { item ->
                sendProtocolLine("HOUSEHOLD_ITEM:${enc(item.id)}:${if (item.done) 1 else 0}:${enc(item.text)}", false)
            }
            sendProtocolLine("HOUSEHOLD_ALERT_STATE:${if (SupermarketGeofenceManager.isEnabled(context)) 1 else 0}", false)
            return sendProtocolLine("HOUSEHOLD_END", false)
        }

        fun sendParkingSnapshot(context: Context): Boolean {
            ParkingAddressStore.init(context.applicationContext)
            if (!isRadioConnected()) return false
            sendProtocolLine("PARKING_BEGIN", false)
            ParkingAddressStore.getAll().forEach { item ->
                sendProtocolLine("PARKING_ITEM:${enc(item.id)}:${enc(item.address)}", false)
            }
            sendProtocolLine("PARKING_TIMER:${ParkingTimerStore.get(context) ?: -1L}", false)
            return sendProtocolLine("PARKING_END", false)
        }

        fun sendSupermarketAlert(items: List<String>): Boolean {
            if (items.isEmpty()) return false
            val text = if (items.size <= 5) items.joinToString(", ") else items.take(5).joinToString(", ") + " en ${items.size - 5} meer"
            return sendProtocolLine("SUPERMARKET_ALERT:${enc("Vergeet niet: $text")}", false)
        }

        fun sendMediaBytes(contact: String, mime: String, bytes: ByteArray): Boolean {
            if (bytes.isEmpty() || bytes.size > 12_000_000) return false
            val id = System.currentTimeMillis().toString(36)
            val chunkSize = if (activeTransport == "Wi-Fi") 24_000 else 8_000
            synchronized(writeLock) {
                val writer = activeWriter ?: return false
                return try {
                    writer.write("MEDIA_BEGIN:$id:${enc(mime)}:${bytes.size}:${enc(contact)}")
                    writer.newLine()
                    var offset = 0
                    while (offset < bytes.size) {
                        val len = minOf(chunkSize, bytes.size - offset)
                        val chunk = Base64.encodeToString(bytes, offset, len, Base64.NO_WRAP)
                        writer.write("MEDIA_CHUNK:$id:$chunk")
                        writer.newLine()
                        offset += len
                    }
                    writer.write("MEDIA_END:$id")
                    writer.newLine(); writer.flush(); true
                } catch (_: Exception) { clearActiveLocked(); false }
            }
        }

        private fun enc(text: String): String =
            Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        private fun sendProtocolLine(line: String, queueIfOffline: Boolean): Boolean {
            synchronized(writeLock) {
                val writer = activeWriter
                if (writer == null) {
                    if (queueIfOffline) enqueueLocked(line)
                    return false
                }
                return try {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                    true
                } catch (_: Exception) {
                    if (queueIfOffline) enqueueLocked(line)
                    clearActiveLocked()
                    false
                }
            }
        }

        private fun enqueueLocked(line: String) {
            while (pendingNotifications.size >= MAX_PENDING) pendingNotifications.removeFirst()
            pendingNotifications.addLast(line)
        }

        /** Wi-Fi=30, UUID=20, CH8=10. Hogere transport mag lagere overnemen. */
        private fun attachConnection(closeable: Closeable, writer: BufferedWriter, priority: Int, transport: String): Long? {
            synchronized(writeLock) {
                if (activeWriter != null && activePriority > priority) return null
                try { activeConnection?.close() } catch (_: Exception) {}
                val id = connectionCounter.incrementAndGet()
                activeConnection = closeable
                activeWriter = writer
                activeConnectionId = id
                activePriority = priority
                activeTransport = transport
                return id
            }
        }

        private fun detachConnection(id: Long): Boolean {
            synchronized(writeLock) {
                if (activeConnectionId != id) return false
                clearActiveLocked()
                return true
            }
        }

        private fun clearActiveLocked() {
            activeWriter = null
            try { activeConnection?.close() } catch (_: Exception) {}
            activeConnection = null
            activeConnectionId = 0L
            activePriority = 0
            activeTransport = "-"
        }

        private fun flushPending() {
            synchronized(writeLock) {
                val writer = activeWriter ?: return
                while (pendingNotifications.isNotEmpty()) {
                    val line = pendingNotifications.first()
                    try {
                        writer.write(line)
                        writer.newLine()
                        writer.flush()
                        pendingNotifications.removeFirst()
                    } catch (_: Exception) {
                        clearActiveLocked()
                        return
                    }
                }
            }
        }
    }

    @Volatile private var running = false
    private var uuidServerSocket: BluetoothServerSocket? = null
    private var fixedServerSocket: BluetoothServerSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private val idleStopRunnable = Runnable {
        if (!isRadioConnected()) {
            CarRadioForwarder.setNearby(this, false)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        running = true
        Thread({ listenWifiTcpLoop() }, "TheOne-WifiServer").start()
        Thread({ discoveryResponderLoop() }, "TheOne-WifiDiscovery").start()
        Thread({ listenBluetoothFixedLoop() }, "TheOne-BtCh8Server").start()
        // Veiligheidsnet voor headunits/telefoons die soms geen ACL_DISCONNECTED sturen.
        // Zonder echte app-verbinding blijft de wachtmelding maximaal 90 seconden staan.
        mainHandler.postDelayed(idleStopRunnable, 90_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Autoradio-verbinding", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildStatusNotification("Wacht op The One Car via Wi-Fi of CH8..."))
    }

    private fun buildStatusNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("The One – Autoradio")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true)
        .build()

    private fun updateStatus(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildStatusNotification(text))
    }

    // ---------------- Wi-Fi/LAN ----------------

    private fun listenWifiTcpLoop() {
        while (running) {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(WIFI_TCP_PORT))
                    soTimeout = 1500
                }
                tcpServerSocket = server
                while (running) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    Thread({ serveWifiSocket(socket) }, "TheOne-WifiSession").start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi server tijdelijk niet beschikbaar", e)
                sleepQuietly(1200)
            } finally {
                try { tcpServerSocket?.close() } catch (_: Exception) {}
                tcpServerSocket = null
            }
        }
    }

    private fun serveWifiSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val auth = reader.readLine()?.trim()
            if (auth != "AUTH:$WIFI_AUTH") {
                socket.close()
                return
            }
            socket.soTimeout = 0
            runProtocolSession(socket, reader, writer, priority = 30, transport = "Wi-Fi")
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun discoveryResponderLoop() {
        while (running) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(WIFI_DISCOVERY_PORT))
                    soTimeout = 1200
                }
                discoverySocket = socket
                val buffer = ByteArray(128)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (text == WIFI_DISCOVER) {
                        val reply = "$WIFI_REPLY_PREFIX$WIFI_TCP_PORT".toByteArray(Charsets.UTF_8)
                        try { socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi discovery tijdelijk niet beschikbaar", e)
                sleepQuietly(1000)
            } finally {
                try { discoverySocket?.close() } catch (_: Exception) {}
                discoverySocket = null
            }
        }
    }

    // ---------------- Bluetooth fallback ----------------

    private fun listenBluetoothUuidLoop() {
        while (running) {
            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
                if (!adapter.isEnabled) {
                    sleepQuietly(1500)
                    continue
                }
                val server = adapter.listenUsingInsecureRfcommWithServiceRecord("TheOneCarRadioV3", APP_UUID)
                uuidServerSocket = server
                socket = server.accept()
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                runProtocolSession(socket, reader, writer, priority = 20, transport = "BT-UUID")
            } catch (_: SecurityException) {
                sleepQuietly(1800)
            } catch (e: Exception) {
                Log.w(TAG, "UUID Bluetooth server herstart", e)
                sleepQuietly(900)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                try { uuidServerSocket?.close() } catch (_: Exception) {}
                uuidServerSocket = null
            }
        }
    }

    private fun listenBluetoothFixedLoop() {
        while (running) {
            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
                if (!adapter.isEnabled) {
                    sleepQuietly(1800)
                    continue
                }
                val server = createFixedChannelServerSocket(adapter)
                if (server == null) {
                    sleepQuietly(2500)
                    continue
                }
                fixedServerSocket = server
                socket = server.accept()
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                runProtocolSession(socket, reader, writer, priority = 10, transport = "BT-CH8")
            } catch (_: SecurityException) {
                sleepQuietly(1800)
            } catch (e: Exception) {
                Log.w(TAG, "CH8 Bluetooth fallback herstart", e)
                sleepQuietly(1200)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                try { fixedServerSocket?.close() } catch (_: Exception) {}
                fixedServerSocket = null
            }
        }
    }

    // ---------------- Protocol ----------------

    private fun runProtocolSession(
        closeable: Closeable,
        reader: BufferedReader,
        writer: BufferedWriter,
        priority: Int,
        transport: String
    ) {
        val connectionId = attachConnection(closeable, writer, priority, transport)
        if (connectionId == null) {
            try { closeable.close() } catch (_: Exception) {}
            return
        }

        mainHandler.removeCallbacks(idleStopRunnable)
        CarRadioForwarder.setNearby(this, true)
        updateStatus("Verbonden met autoradio • $transport")
        sendProtocolLine("SYS:HELLO:THE_ONE_CAR", false)
        flushPending()
        sendContactSnapshot()
        sendHouseholdSnapshot(this)
        sendParkingSnapshot(this)

        var voiceId: String? = null
        var voiceBytes: ByteArrayOutputStream? = null
        var voiceTarget: String? = null

        try {
            while (running && activeConnectionId == connectionId) {
                val command = reader.readLine()?.trim() ?: break
                if (command.isEmpty()) continue

                when {
                    command.startsWith("SYS:PING:") -> {
                        sendProtocolLine("SYS:PONG:${command.removePrefix("SYS:PING:")}", false)
                    }
                    command == "CONTACTS_REQUEST" -> sendContactSnapshot()
                    command.startsWith("CONTACT_FILTER:") -> {
                        WhatsAppCarFilterStore.setFilterEnabled(this, command.removePrefix("CONTACT_FILTER:") == "1")
                        sendContactSnapshot()
                    }
                    command.startsWith("CONTACT_ALLOW:") -> {
                        val parts = command.split(":", limit = 3)
                        if (parts.size == 3) {
                            val name = dec(parts[2])
                            if (name.isNotBlank()) WhatsAppCarFilterStore.setAllowed(this, name, parts[1] == "1")
                            sendContactSnapshot()
                        }
                    }
                    command.startsWith("CONTACT_REMOVE:") -> {
                        val name = dec(command.removePrefix("CONTACT_REMOVE:"))
                        if (name.isNotBlank()) {
                            WhatsAppCarFilterStore.removeContact(this, name)
                            sendContactSnapshot()
                        }
                    }
                    command == "HOUSEHOLD_REQUEST" -> sendHouseholdSnapshot(this)
                    command.startsWith("HOUSEHOLD_ADD:") -> {
                        val text = dec(command.removePrefix("HOUSEHOLD_ADD:"))
                        ShoppingListStore.init(applicationContext)
                        if (text.isNotBlank()) ShoppingListStore.add(text)
                        sendHouseholdSnapshot(this)
                    }
                    command.startsWith("HOUSEHOLD_TOGGLE:") -> {
                        val id = dec(command.removePrefix("HOUSEHOLD_TOGGLE:"))
                        ShoppingListStore.init(applicationContext)
                        if (id.isNotBlank()) ShoppingListStore.toggleDone(id)
                        sendHouseholdSnapshot(this)
                    }
                    command.startsWith("HOUSEHOLD_REMOVE:") -> {
                        val id = dec(command.removePrefix("HOUSEHOLD_REMOVE:"))
                        ShoppingListStore.init(applicationContext)
                        if (id.isNotBlank()) ShoppingListStore.remove(id)
                        sendHouseholdSnapshot(this)
                    }
                    command == "HOUSEHOLD_CLEAR_DONE" -> {
                        ShoppingListStore.init(applicationContext)
                        ShoppingListStore.clearDone()
                        sendHouseholdSnapshot(this)
                    }
                    command.startsWith("HOUSEHOLD_ALERT_SET:") -> {
                        val enabled = command.removePrefix("HOUSEHOLD_ALERT_SET:") == "1"
                        if (!enabled) {
                            SupermarketGeofenceManager.disable(this)
                            SupermarketRefreshWorker.cancel(this)
                            sendHouseholdSnapshot(this)
                        } else if (SupermarketGeofenceManager.hasLocationPermission(this)) {
                            SupermarketGeofenceManager.enableForCurrentLocation(this) { _, _ -> sendHouseholdSnapshot(this) }
                            SupermarketRefreshWorker.schedule(this)
                        } else {
                            sendProtocolLine("STATUS:Zet supermarkt-meldingen één keer op je telefoon aan om locatietoestemming te geven.", false)
                            sendHouseholdSnapshot(this)
                        }
                    }
                    command == "PARKING_REQUEST" -> sendParkingSnapshot(this)
                    command.startsWith("PARKING_ADD:") -> {
                        val address = dec(command.removePrefix("PARKING_ADD:"))
                        ParkingAddressStore.init(applicationContext)
                        val added = if (address.isNotBlank()) ParkingAddressStore.add(address) else null
                        if (added != null && ParkingGeofenceManager.hasLocationPermission(this)) {
                            ParkingGeofenceManager.registerNewAddress(this, added.id, added.address)
                        }
                        sendParkingSnapshot(this)
                    }
                    command.startsWith("PARKING_REMOVE:") -> {
                        val id = dec(command.removePrefix("PARKING_REMOVE:"))
                        ParkingAddressStore.init(applicationContext)
                        if (id.isNotBlank()) {
                            ParkingAddressStore.remove(id)
                            ParkingGeofenceManager.unregister(this, id)
                        }
                        sendParkingSnapshot(this)
                    }
                    command.startsWith("PARKING_TIMER_SET:") -> {
                        val epoch = command.removePrefix("PARKING_TIMER_SET:").toLongOrNull()
                        if (epoch != null && epoch > System.currentTimeMillis()) scheduleParkingTimer(epoch)
                        sendParkingSnapshot(this)
                    }
                    command == "PARKING_TIMER_CLEAR" -> {
                        clearParkingTimer()
                        sendParkingSnapshot(this)
                    }
                    command.startsWith("VOICE_NOTE_REQUEST:") -> {
                        val contact = dec(command.removePrefix("VOICE_NOTE_REQUEST:"))
                        handleVoiceNoteRequest(contact)
                    }
                    command.startsWith("REPLY_TEXT_TO:") -> {
                        val parts = command.split(":", limit = 3)
                        if (parts.size == 3) handleRecognizedReply(dec(parts[2]), dec(parts[1]))
                    }
                    command.startsWith("REPLY_TEXT:") -> handleRecognizedReply(dec(command.removePrefix("REPLY_TEXT:")), null)
                    command == "REPLY_REQUEST" -> handleReplyRequest(null)
                    command.startsWith("REPLY_REQUEST_TO:") -> handleReplyRequest(dec(command.removePrefix("REPLY_REQUEST_TO:")))
                    command.startsWith("VOICE_BEGIN:") -> {
                        val parts = command.split(":", limit = 5)
                        if (parts.size >= 4) {
                            voiceId = parts[1]
                            val expected = parts[3].toIntOrNull()?.coerceAtMost(2_200_000) ?: 0
                            voiceTarget = if (parts.size == 5) dec(parts[4]).takeIf { it.isNotBlank() } else null
                            voiceBytes = ByteArrayOutputStream(expected.coerceAtLeast(32_000))
                            sendProtocolLine("STATUS:Audio ontvangen — verwerken...", false)
                        }
                    }
                    command.startsWith("VOICE_CHUNK:") -> {
                        val parts = command.split(":", limit = 3)
                        if (parts.size == 3 && parts[1] == voiceId) {
                            val decoded = try { Base64.decode(parts[2], Base64.DEFAULT) } catch (_: Exception) { null }
                            val current = voiceBytes
                            if (decoded != null && current != null && current.size() + decoded.size <= 2_200_000) current.write(decoded)
                        }
                    }
                    command.startsWith("VOICE_END:") -> {
                        if (command.removePrefix("VOICE_END:") == voiceId) {
                            val bytes = voiceBytes?.toByteArray() ?: ByteArray(0)
                            val target = voiceTarget
                            voiceId = null
                            voiceBytes = null
                            voiceTarget = null
                            handleVoiceAudio(bytes, target)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$transport sessie verbroken", e)
        } finally {
            val wasActive = detachConnection(connectionId)
            if (wasActive) {
                // Bluetooth ACL kan nog actief zijn terwijl alleen onze app-socket kort wegvalt.
                // Geef de radio tijd om zelf opnieuw te verbinden, maar laat de melding niet eindeloos staan.
                updateStatus("Verbinding weg — automatisch opnieuw verbinden...")
                mainHandler.removeCallbacks(idleStopRunnable)
                mainHandler.postDelayed(idleStopRunnable, 90_000L)
            }
            try { closeable.close() } catch (_: Exception) {}
        }
    }

    private fun scheduleParkingTimer(triggerAtMillis: Long) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            this, 3, Intent(this, ParkingTimerReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            ParkingTimerStore.set(this, triggerAtMillis)
        } catch (_: Exception) {
            sendProtocolLine("STATUS:Parkeertijd kon niet worden ingesteld op je telefoon.", false)
        }
    }

    private fun clearParkingTimer() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            this, 3, Intent(this, ParkingTimerReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try { alarmManager.cancel(pendingIntent) } catch (_: Exception) {}
        ParkingTimerStore.clear(this)
    }

    private fun handleVoiceNoteRequest(contact: String) {
        if (contact.isBlank()) return
        val source = UnifiedNotificationListener.voiceNoteSourceForConversation(contact)
        if (source == null) {
            sendProtocolLine("VOICE_NOTE_STATUS:${encLocal("Geen afspeelbaar spraakbericht meer beschikbaar voor ${contact}.")}", false)
            return
        }
        if (source.uri != null) {
            Thread {
                try {
                    val bytes = contentResolver.openInputStream(source.uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(32 * 1024)
                        while (out.size() <= 12_000_000) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                    if (bytes != null && bytes.isNotEmpty() && bytes.size <= 12_000_000) {
                        sendProtocolLine("VOICE_NOTE_STATUS:${encLocal("Spraakbericht naar de autoradio sturen…")}", false)
                        if (!sendMediaBytes(contact, source.mime ?: "audio/*", bytes)) {
                            sendProtocolLine("VOICE_NOTE_STATUS:${encLocal("Spraakbericht kon niet naar de radio worden gestuurd.")}", false)
                        }
                        return@Thread
                    }
                } catch (_: Exception) {}
                if (UnifiedNotificationListener.triggerVoiceNoteAction(contact)) {
                    sendProtocolLine("VOICE_NOTE_STATUS:${encLocal("Spraakbericht geopend via WhatsApp op je telefoon.")}", false)
                } else {
                    sendProtocolLine("VOICE_NOTE_STATUS:${encLocal("WhatsApp gaf geen afspeelbare audio aan The One door.")}", false)
                }
            }.start()
        } else if (UnifiedNotificationListener.triggerVoiceNoteAction(contact)) {
            sendProtocolLine("VOICE_NOTE_STATUS:${encLocal("Spraakbericht geopend via WhatsApp op je telefoon.")}", false)
        } else {
            sendProtocolLine("VOICE_NOTE_STATUS:${encLocal("WhatsApp gaf geen afspeelbare audio aan The One door.")}", false)
        }
    }

    private fun sendContactSnapshot() {
        sendProtocolLine("CONTACTS_BEGIN:${if (WhatsAppCarFilterStore.isFilterEnabled(this)) 1 else 0}", false)
        val allowed = WhatsAppCarFilterStore.allowedContacts(this)
        WhatsAppCarFilterStore.knownContacts(this)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .forEach { name ->
                val isAllowed = allowed.any { it.equals(name, ignoreCase = true) }
                sendProtocolLine("CONTACT:${if (isAllowed) 1 else 0}:${encLocal(name)}", false)
            }
        sendProtocolLine("CONTACTS_END", false)
    }

    private fun handleVoiceAudio(wavBytes: ByteArray, target: String?) {
        if (wavBytes.size < 1000) {
            sendProtocolLine("STATUS:Geen bruikbare audio ontvangen. Probeer opnieuw.", false)
            return
        }

        // Volledige transcriptie en snelheid tegelijk: start Gemini en de lokale
        // Android-herkenner parallel. Gemini krijgt voorrang omdat die de hele
        // opname verwerkt (ook na natuurlijke pauzes). De snelle lokale tekst
        // is alleen fallback als Gemini niet binnen enkele seconden klaar is.
        sendProtocolLine("STATUS:Spraak wordt omgezet naar tekst...", false)
        val delivered = AtomicBoolean(false)
        var fastText: String? = null

        fun deliver(text: String) {
            val cleaned = text.trim()
            if (cleaned.isBlank()) return
            if (delivered.compareAndSet(false, true)) {
                mainHandler.post { finishReply(target, cleaned) }
            }
        }

        GeminiVoiceTranscriber.transcribe(wavBytes) { result ->
            when (result) {
                is GeminiVoiceTranscriber.Result.Success -> deliver(result.text)
                is GeminiVoiceTranscriber.Result.Error -> {
                    val fallback = fastText
                    if (!fallback.isNullOrBlank()) deliver(fallback)
                    else if (delivered.compareAndSet(false, true)) {
                        mainHandler.post {
                            sendProtocolLine("STATUS:Spraak omzetten mislukt (${result.message}). Ik probeer de telefoonmicrofoon.", false)
                            handleReplyRequest(target)
                        }
                    }
                }
            }
        }

        InjectedAudioSpeechTranscriber.transcribe(this, wavBytes) { fastResult ->
            if (fastResult is InjectedAudioSpeechTranscriber.Result.Success) {
                fastText = fastResult.text.trim()
            }
        }

        // De lokale herkenner is alleen fallback wanneer Gemini zelf faalt.
        // Zo voorkomen we dat een vroege eerste zin uit Android al wordt verstuurd
        // terwijl er later in dezelfde opname nog meer is gezegd.
    }

    private fun finishReply(target: String?, text: String) {
        if (text.isBlank()) {
            sendProtocolLine("STATUS:Kon je antwoord niet verstaan, probeer opnieuw.", false)
            return
        }
        val ok = if (!target.isNullOrBlank()) {
            UnifiedNotificationListener.sendReplyToConversation(target, text)
        } else {
            val key = UnifiedNotificationListener.lastWhatsAppReplyKey
            key != null && UnifiedNotificationListener.sendReply(key, text)
        }
        if (ok) {
            if (!target.isNullOrBlank()) {
                sendProtocolLine("WA_SENT:${encLocal(target)}:${encLocal(text)}:${System.currentTimeMillis()}", false)
            }
            sendProtocolLine("STATUS:Antwoord verzonden: $text", false)
        } else {
            val message = if (!target.isNullOrBlank())
                "Geen actieve WhatsApp-antwoordknop meer voor $target. Wacht op een nieuw bericht van dit gesprek."
            else "Versturen mislukt, open WhatsApp zelf."
            sendProtocolLine("STATUS:$message", false)
        }
    }

    private fun handleRecognizedReply(text: String, target: String?) {
        mainHandler.post { finishReply(target, text.trim()) }
    }

    /** Fallback via de telefoonmicrofoon als audiotranscriptie niet lukt. */
    private fun handleReplyRequest(target: String?) {
        mainHandler.post {
            val canReply = if (!target.isNullOrBlank()) UnifiedNotificationListener.hasReplyTarget(target)
            else UnifiedNotificationListener.lastWhatsAppReplyKey != null
            if (!canReply) {
                sendProtocolLine("STATUS:Geen recent WhatsApp-bericht voor dit gesprek om op te antwoorden.", false)
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                sendProtocolLine("STATUS:Spraakherkenning niet beschikbaar op je telefoon.", false)
                return@post
            }

            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer
            var lastPartialSpeech: String? = null
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: lastPartialSpeech
                    finishReply(target, text.orEmpty())
                    recognizer.destroy()
                }
                override fun onError(error: Int) {
                    sendProtocolLine("STATUS:Telefoonspraakherkenning mislukte (foutcode $error).", false)
                    recognizer.destroy()
                }
                override fun onReadyForSpeech(params: android.os.Bundle?) { sendProtocolLine("STATUS:Spreek nu richting je telefoon...", false) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (!partial.isNullOrBlank()) lastPartialSpeech = partial
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            try { recognizer.startListening(intent) } catch (_: Exception) {
                sendProtocolLine("STATUS:Telefoonspraakherkenning kon niet starten.", false)
                recognizer.destroy()
            }
        }
    }

    private fun encLocal(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun dec(value: String): String = try {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) { "" }

    private fun createFixedChannelServerSocket(adapter: BluetoothAdapter): BluetoothServerSocket? {
        return try {
            val method = adapter.javaClass.getMethod("listenUsingInsecureRfcommOn", Int::class.javaPrimitiveType)
            method.invoke(adapter, FIXED_RFCOMM_CHANNEL) as? BluetoothServerSocket
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        running = false
        synchronized(writeLock) { clearActiveLocked() }
        try { uuidServerSocket?.close() } catch (_: Exception) {}
        try { fixedServerSocket?.close() } catch (_: Exception) {}
        try { tcpServerSocket?.close() } catch (_: Exception) {}
        try { discoverySocket?.close() } catch (_: Exception) {}
        mainHandler.removeCallbacks(idleStopRunnable)
        speechRecognizer?.destroy()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
