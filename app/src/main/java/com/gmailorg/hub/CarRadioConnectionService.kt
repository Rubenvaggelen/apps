package com.gmailorg.hub

import android.app.NotificationChannel
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
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.UUID

/** Telefoonkant van The One Car. De telefoon-UI blijft verder ongewijzigd. */
class CarRadioConnectionService : Service() {

    companion object {
        private val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
        private const val TAG = "CarRadioConnection"
        private const val CHANNEL_ID = "car_radio_connection"
        private const val NOTIFICATION_ID = 2
        private const val MAX_PENDING = 40
        private const val FIXED_RFCOMM_CHANNEL = 8

        private val writeLock = Any()
        private val pendingNotifications = ArrayDeque<String>()

        @Volatile private var activeWriter: BufferedWriter? = null
        @Volatile private var activeSocket: BluetoothSocket? = null

        fun start(context: Context) {
            val intent = Intent(context, CarRadioConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarRadioConnectionService::class.java))
        }

        fun isRadioConnected(): Boolean = activeWriter != null

        /** Legacy tekstpad voor statusregels en oude radio-versies. */
        fun sendMessage(text: String): Boolean {
            val line = when {
                text.startsWith("STATUS:") || text.startsWith("SYS:") -> text
                else -> "MSG:" + enc(text)
            }
            return sendProtocolLine(line, line.startsWith("MSG:"))
        }

        fun sendWhatsAppMessage(title: String, text: String, postTime: Long): Boolean {
            val line = "WA_MSG:${enc(title)}:${enc(text)}:$postTime"
            return sendProtocolLine(line, true)
        }

        fun sendContactState(name: String, allowed: Boolean, filterEnabled: Boolean): Boolean {
            val line = "CONTACT_STATE:${if (filterEnabled) 1 else 0}:${if (allowed) 1 else 0}:${enc(name)}"
            return sendProtocolLine(line, false)
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
                    activeWriter = null
                    try { activeSocket?.close() } catch (_: Exception) {}
                    activeSocket = null
                    if (queueIfOffline) enqueueLocked(line)
                    false
                }
            }
        }

        private fun enqueueLocked(line: String) {
            while (pendingNotifications.size >= MAX_PENDING) pendingNotifications.removeFirst()
            pendingNotifications.addLast(line)
        }

        private fun attachConnection(socket: BluetoothSocket, writer: BufferedWriter) {
            synchronized(writeLock) {
                try { activeSocket?.close() } catch (_: Exception) {}
                activeSocket = socket
                activeWriter = writer
            }
        }

        private fun detachConnection(socket: BluetoothSocket) {
            synchronized(writeLock) {
                if (activeSocket === socket) {
                    activeWriter = null
                    activeSocket = null
                }
            }
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
                        activeWriter = null
                        try { activeSocket?.close() } catch (_: Exception) {}
                        activeSocket = null
                        return
                    }
                }
            }
        }
    }

    private var running = false
    private var serverSocket: BluetoothServerSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        running = true
        Thread({ listenLoop() }, "TheOne-RfcommServer").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Autoradio-verbinding",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildStatusNotification("Wacht op verbinding met je autoradio..."))
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

    private fun listenLoop() {
        while (running) {
            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
                updateStatus("Wacht op verbinding met je autoradio...")
                if (serverSocket == null) {
                    // K2401: eerst het vaste kanaal 8 gebruiken. Dit is de route die op
                    // deze specifieke headunit aantoonbaar stabiel berichten kon ontvangen.
                    serverSocket = createFixedChannelServerSocket(adapter)
                        ?: adapter.listenUsingInsecureRfcommWithServiceRecord(
                            "TheOneCarRadioV2", APP_UUID
                        )
                }

                socket = serverSocket?.accept() ?: continue
                val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                attachConnection(socket, writer)
                CarRadioForwarder.setNearby(this, true)
                updateStatus("Verbonden met autoradio • live")

                sendProtocolLine("SYS:HELLO:THE_ONE_CAR", false)
                flushPending()
                sendContactSnapshot()

                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                var voiceId: String? = null
                var voiceBytes: ByteArrayOutputStream? = null
                var voiceTarget: String? = null

                while (running) {
                    val command = reader.readLine()?.trim() ?: break
                    if (command.isEmpty()) continue

                    when {
                        command.startsWith("SYS:PING:") -> {
                            val token = command.removePrefix("SYS:PING:")
                            sendProtocolLine("SYS:PONG:$token", false)
                        }
                        command == "CONTACTS_REQUEST" -> sendContactSnapshot()
                        command.startsWith("CONTACT_FILTER:") -> {
                            val enabled = command.removePrefix("CONTACT_FILTER:") == "1"
                            WhatsAppCarFilterStore.setFilterEnabled(this, enabled)
                            sendContactSnapshot()
                        }
                        command.startsWith("CONTACT_ALLOW:") -> {
                            val parts = command.split(":", limit = 3)
                            if (parts.size == 3) {
                                val allowed = parts[1] == "1"
                                val name = dec(parts[2])
                                if (name.isNotBlank()) WhatsAppCarFilterStore.setAllowed(this, name, allowed)
                                sendContactSnapshot()
                            }
                        }
                        command.startsWith("REPLY_TEXT_TO:") -> {
                            val parts = command.split(":", limit = 3)
                            if (parts.size == 3) {
                                handleRecognizedReply(dec(parts[2]), dec(parts[1]))
                            }
                        }
                        command.startsWith("REPLY_TEXT:") -> {
                            handleRecognizedReply(dec(command.removePrefix("REPLY_TEXT:")), null)
                        }
                        command == "REPLY_REQUEST" -> handleReplyRequest(null)
                        command.startsWith("REPLY_REQUEST_TO:") -> {
                            handleReplyRequest(dec(command.removePrefix("REPLY_REQUEST_TO:")))
                        }
                        command.startsWith("VOICE_BEGIN:") -> {
                            // VOICE_BEGIN:id:audio/wav:size:targetBase64
                            val parts = command.split(":", limit = 5)
                            if (parts.size >= 4) {
                                voiceId = parts[1]
                                val expected = parts[3].toIntOrNull()?.coerceAtMost(1_500_000) ?: 0
                                voiceTarget = if (parts.size == 5) dec(parts[4]).takeIf { it.isNotBlank() } else null
                                voiceBytes = ByteArrayOutputStream(expected.coerceAtLeast(32_000))
                                sendProtocolLine("STATUS:Audio ontvangen — even verwerken...", false)
                            }
                        }
                        command.startsWith("VOICE_CHUNK:") -> {
                            val parts = command.split(":", limit = 3)
                            if (parts.size == 3 && parts[1] == voiceId) {
                                val decoded = try { Base64.decode(parts[2], Base64.DEFAULT) } catch (_: Exception) { null }
                                if (decoded != null) {
                                    val current = voiceBytes
                                    if (current != null && current.size() + decoded.size <= 1_500_000) current.write(decoded)
                                }
                            }
                        }
                        command.startsWith("VOICE_END:") -> {
                            val id = command.removePrefix("VOICE_END:")
                            if (id == voiceId) {
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
            } catch (e: SecurityException) {
                Log.w(TAG, "Geen Bluetooth-toestemming", e)
                updateStatus("⚠️ Geen Bluetooth-toestemming")
                sleepQuietly(3000)
            } catch (e: Exception) {
                Log.w(TAG, "Autoradio-verbinding weg", e)
                updateStatus("Verbinding verbroken — wachten op autoradio...")
                sleepQuietly(1500)
            } finally {
                if (socket != null) detachConnection(socket)
                CarRadioForwarder.setNearby(this, false)
                try { socket?.close() } catch (_: Exception) {}
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
                if (running) updateStatus("Wacht op verbinding met je autoradio...")
            }
        }
    }

    private fun sendContactSnapshot() {
        sendProtocolLine(
            "CONTACTS_BEGIN:${if (WhatsAppCarFilterStore.isFilterEnabled(this)) 1 else 0}",
            false
        )
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

        sendProtocolLine("STATUS:Spraak wordt op je telefoon omgezet naar tekst...", false)
        GeminiVoiceTranscriber.transcribe(wavBytes) { result ->
            when (result) {
                is GeminiVoiceTranscriber.Result.Success -> {
                    val text = result.text.trim()
                    finishReply(target, text)
                }
                is GeminiVoiceTranscriber.Result.Error -> {
                    sendProtocolLine("STATUS:Spraak omzetten mislukt (${result.message}). Ik probeer de telefoonmicrofoon.", false)
                    handleReplyRequest(target)
                }
            }
        }
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
            val resolvedTarget = target.orEmpty()
            if (resolvedTarget.isNotBlank()) {
                sendProtocolLine("WA_SENT:${encLocal(resolvedTarget)}:${encLocal(text)}:${System.currentTimeMillis()}", false)
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

    /** Fallback via de telefoonmicrofoon. */
    private fun handleReplyRequest(target: String?) {
        mainHandler.post {
            val canReply = if (!target.isNullOrBlank()) {
                UnifiedNotificationListener.hasReplyTarget(target)
            } else {
                UnifiedNotificationListener.lastWhatsAppReplyKey != null
            }
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
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    sendProtocolLine("STATUS:Spreek nu richting je telefoon...", false)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim()
                    if (!partial.isNullOrBlank()) lastPartialSpeech = partial
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            try {
                recognizer.startListening(intent)
            } catch (_: Exception) {
                sendProtocolLine("STATUS:Telefoonspraakherkenning kon niet starten.", false)
                recognizer.destroy()
            }
        }
    }

    private fun encLocal(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun dec(value: String): String = try {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    private fun createFixedChannelServerSocket(adapter: BluetoothAdapter): BluetoothServerSocket? {
        return try {
            val method = adapter.javaClass.getMethod(
                "listenUsingInsecureRfcommOn", Int::class.javaPrimitiveType
            )
            method.invoke(adapter, FIXED_RFCOMM_CHANNEL) as? BluetoothServerSocket
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        running = false
        synchronized(writeLock) {
            activeWriter = null
            try { activeSocket?.close() } catch (_: Exception) {}
            activeSocket = null
        }
        try { serverSocket?.close() } catch (_: Exception) {}
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
