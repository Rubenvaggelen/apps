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
import android.util.Log
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Luistert op een Bluetooth-verbinding (RFCOMM) waarmee de autoradio
 * verbindt — de telefoon is de "server", de autoradio is de "client". Dit
 * is bewust omgedraaid t.o.v. de eerdere opzet: de Bluetooth-stack van de
 * hoofdunit bleek geen inkomende verbindingen te kunnen aannemen
 * (IOException "Error: -1", ook niet met een vast kanaalnummer in plaats
 * van SDP). Telefoons hebben doorgaans een robuustere Bluetooth-stack voor
 * het hosten van verbindingen, dus die rol is nu hier belegd — de auto
 * neemt het initiatief, zoals ook al gebeurt voor bellen/audio.
 */
class CarRadioConnectionService : Service() {

    companion object {
        // Moet exact overeenkomen met BluetoothListenerService.APP_UUID in de carradio-module.
        private val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
        // Vast RFCOMM-kanaalnummer (omzeilt SDP) — moet exact overeenkomen
        // met FIXED_RFCOMM_CHANNEL in BluetoothListenerService.kt op de autoradio.
        private const val FIXED_RFCOMM_CHANNEL = 8
        private const val TAG = "CarRadioConnection"
        private const val CHANNEL_ID = "car_radio_connection"
        private const val NOTIFICATION_ID = 2
        private const val CMD_REPLY_REQUEST = "REPLY_REQUEST"
        private const val CMD_REPLY_TEXT_PREFIX = "REPLY_TEXT:"
        private const val HANDSHAKE_RADIO = "THE_ONE_RADIO_HELLO_V1"
        private const val HANDSHAKE_PHONE = "THE_ONE_PHONE_OK_V1"
        private const val HANDSHAKE_TIMEOUT_MS = 8000L

        @Volatile
        private var outputStream: OutputStream? = null

        fun start(context: Context) {
            val intent = Intent(context, CarRadioConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarRadioConnectionService::class.java))
        }

        /** Stuurt tekst (bv. een WhatsApp-melding) door naar de autoradio, indien verbonden. */
        fun sendMessage(text: String): Boolean {
            val out = outputStream ?: return false
            return try {
                out.write("$text\n".toByteArray())
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private var running = false
    private val serverSockets = mutableListOf<BluetoothServerSocket>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        running = true
        Thread { listenLoop() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Autoradio-verbinding", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The One – Autoradio")
            .setContentText("Wacht op verbinding met je autoradio...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    /** Werkt de meldingstekst bij met de echte verbindingsstatus, zodat je die kunt checken via je telefoon. */
    private fun updateStatus(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The One – Autoradio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun listenLoop() {
        while (running) {
            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
                updateStatus("Wacht op verbinding met je autoradio...")

                // Luister tegelijk via de normale app-UUID én (indien mogelijk)
                // via het oude vaste RFCOMM-kanaal. De handshake hieronder
                // voorkomt dat de autoradio per ongeluk een ander Bluetooth-
                // profiel op hetzelfde kanaal als "The One" beschouwt.
                socket = waitForVerifiedAppSocket(adapter) ?: continue
                outputStream = socket.outputStream
                updateStatus("Verbonden met autoradio")
                CarRadioForwarder.setNearby(this, true)
                // Zichtbare bevestiging op de radio dat niet alleen Bluetooth,
                // maar echt de The One-app aan beide kanten verbonden is.
                sendMessage("STATUS:The One-koppeling bevestigd")

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                var line: String?
                while (running) {
                    line = reader.readLine() ?: break
                    val command = line.trim()
                    when {
                        command == CMD_REPLY_REQUEST -> handleReplyRequest()
                        command.startsWith(CMD_REPLY_TEXT_PREFIX) -> {
                            val encoded = command.removePrefix(CMD_REPLY_TEXT_PREFIX)
                            val text = try {
                                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                            } catch (e: Exception) {
                                ""
                            }
                            handleRecognizedReply(text)
                        }
                    }
                }
                updateStatus("Verbinding verbroken — wachten op nieuwe verbinding...")
            } catch (e: SecurityException) {
                Log.w(TAG, "Geen Bluetooth-toestemming", e)
                updateStatus("⚠️ Geen Bluetooth-toestemming — zet dit in Instellingen nogmaals aan.")
                Thread.sleep(3000)
            } catch (e: Exception) {
                Log.w(TAG, "Autoradio-verbinding niet beschikbaar, opnieuw proberen", e)
                updateStatus("⚠️ Kan geen verbinding maken (${e.javaClass.simpleName}) — opnieuw proberen...")
                Thread.sleep(3000)
            } finally {
                outputStream = null
                try { socket?.close() } catch (_: Exception) { }
                closeServerSockets()
            }
        }
    }

    /**
     * Wacht op een echte The One-verbinding. Alleen socket.connect() is niet
     * voldoende: een vast RFCOMM-kanaal kan op sommige head-units/telefoons
     * ook door een ander Bluetooth-profiel gebruikt worden. Daarom accepteren
     * we de verbinding pas na een app-specifieke handshake.
     */
    private fun waitForVerifiedAppSocket(adapter: BluetoothAdapter): BluetoothSocket? {
        closeServerSockets()
        val accepted = LinkedBlockingQueue<BluetoothSocket>()

        // Normale UUID/SDP-listener is de voorkeursroute.
        try {
            val uuidServer = adapter.listenUsingRfcommWithServiceRecord("TheOneCarRadio", APP_UUID)
            addServerSocket(uuidServer)
            startAcceptThread(uuidServer, accepted, "uuid")
        } catch (e: Exception) {
            Log.w(TAG, "UUID-listener kon niet starten", e)
        }

        // Compatibiliteitsroute voor oudere/afwijkende autoradio-stacks.
        try {
            val fixedServer = createFixedChannelServerSocket(adapter)
            if (fixedServer != null) {
                addServerSocket(fixedServer)
                startAcceptThread(fixedServer, accepted, "fixed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vaste RFCOMM-listener kon niet starten", e)
        }

        synchronized(serverSockets) {
            if (serverSockets.isEmpty()) {
                throw IOException("Geen Bluetooth-server kon worden gestart")
            }
        }

        while (running) {
            val candidate = accepted.poll(1, TimeUnit.SECONDS) ?: continue
            try {
                val input = candidate.inputStream
                val out = candidate.outputStream
                val hello = readLineWithTimeout(input, HANDSHAKE_TIMEOUT_MS)
                if (hello != HANDSHAKE_RADIO) {
                    Log.w(TAG, "Socket geweigerd: ongeldige The One-handshake: $hello")
                    candidate.close()
                    continue
                }

                out.write("$HANDSHAKE_PHONE\n".toByteArray(Charsets.UTF_8))
                out.flush()
                closeServerSockets()
                return candidate
            } catch (e: Exception) {
                Log.w(TAG, "Handshake op inkomende socket mislukt", e)
                try { candidate.close() } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun addServerSocket(server: BluetoothServerSocket) {
        synchronized(serverSockets) { serverSockets.add(server) }
    }

    private fun startAcceptThread(
        server: BluetoothServerSocket,
        accepted: LinkedBlockingQueue<BluetoothSocket>,
        route: String
    ) {
        Thread {
            try {
                val socket = server.accept()
                if (running) {
                    accepted.offer(socket)
                } else {
                    try { socket.close() } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                if (running) Log.d(TAG, "Accept via $route gestopt: ${e.message}")
            }
        }.start()
    }

    private fun closeServerSockets() {
        val sockets = synchronized(serverSockets) {
            val copy = serverSockets.toList()
            serverSockets.clear()
            copy
        }
        sockets.forEach { server ->
            try { server.close() } catch (_: Exception) { }
        }
    }

    private fun readLineWithTimeout(input: InputStream, timeoutMs: Long): String? {
        // Bluetooth InputStream.available() is not reliable on every Android
        // Bluetooth stack/head-unit. Read blocking on a tiny worker instead and
        // bound the wait with a latch. Closing the socket after a timeout also
        // releases the worker if it is still blocked in read().
        val latch = CountDownLatch(1)
        var result: String? = null
        var failure: Throwable? = null

        val readerThread = Thread {
            try {
                val builder = StringBuilder()
                while (running) {
                    val value = input.read()
                    if (value == -1) break
                    when (value.toChar()) {
                        '\n' -> {
                            result = builder.toString().trim()
                            break
                        }
                        '\r' -> Unit
                        else -> {
                            if (builder.length >= 512) break
                            builder.append(value.toChar())
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }.apply {
            name = "TheOneHandshakeRead-Phone"
            isDaemon = true
            start()
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        failure?.let { t ->
            if (t is Exception) throw t
            throw IOException("Handshake-read mislukt", t)
        }
        return result
    }

    /**
     * Ontvangt tekst die al op de autoradio zelf is herkend en gebruikt alleen
     * de telefoon voor de WhatsApp RemoteInput-actie. Geen microfoon nodig.
     */
    private fun handleRecognizedReply(text: String) {
        mainHandler.post {
            if (text.isBlank()) {
                sendMessage("STATUS:Kon je antwoord niet verstaan, probeer opnieuw.")
                return@post
            }
            val key = UnifiedNotificationListener.lastWhatsAppReplyKey
            if (key == null) {
                sendMessage("STATUS:Geen recent WhatsApp-bericht om op te antwoorden.")
                return@post
            }
            val ok = UnifiedNotificationListener.sendReply(key, text)
            if (ok) {
                sendMessage("STATUS:Antwoord verzonden: $text")
            } else {
                sendMessage("STATUS:Versturen mislukt, open WhatsApp zelf.")
            }
        }
    }

    /** Start spraakherkenning en stuurt het resultaat als WhatsApp-antwoord, zonder de telefoon te ontgrendelen. */
    private fun handleReplyRequest() {
        mainHandler.post {
            val key = UnifiedNotificationListener.lastWhatsAppReplyKey
            if (key == null) {
                sendMessage("STATUS:Geen recent WhatsApp-bericht om op te antwoorden.")
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                sendMessage("STATUS:Spraakherkenning niet beschikbaar op je telefoon.")
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
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .takeUnless { it.isNullOrBlank() }
                        ?: lastPartialSpeech
                    if (text.isNullOrBlank()) {
                        sendMessage("STATUS:Kon je antwoord niet verstaan, probeer opnieuw.")
                    } else {
                        val ok = UnifiedNotificationListener.sendReply(key, text)
                        if (ok) {
                            sendMessage("STATUS:Antwoord verzonden: $text")
                        } else {
                            sendMessage("STATUS:Versturen mislukt, open WhatsApp zelf.")
                        }
                    }
                    recognizer.destroy()
                }

                override fun onError(error: Int) {
                    val reason = when (error) {
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "geen internetverbinding"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "geen microfoon-toestemming"
                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "niets herkend, probeer duidelijker te spreken"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "te lang gewacht met spreken"
                        SpeechRecognizer.ERROR_AUDIO ->
                            "microfoon-probleem"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "spraakherkenning was al bezig"
                        else -> "foutcode $error"
                    }
                    sendMessage("STATUS:Kon je antwoord niet verstaan ($reason), probeer opnieuw.")
                    recognizer.destroy()
                }

                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    sendMessage("STATUS:Spreek nu je antwoord in...")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (!partial.isNullOrBlank()) lastPartialSpeech = partial
                }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            try {
                recognizer.startListening(intent)
            } catch (e: SecurityException) {
                sendMessage("STATUS:Microfoon is door Android geblokkeerd op de achtergrond. Gebruik spraak via de autoradio.")
                recognizer.destroy()
            } catch (e: Exception) {
                sendMessage("STATUS:Spraakherkenning kon niet starten (${e.javaClass.simpleName}).")
                recognizer.destroy()
            }
        }
    }

    override fun onDestroy() {
        running = false
        outputStream = null
        closeServerSockets()
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Maakt een BluetoothServerSocket op een vast kanaalnummer, zonder SDP-
     * registratie — via reflectie, omdat deze methode niet in de publieke
     * Android-SDK zit maar wel bestaat in de onderliggende implementatie.
     * Geeft null terug als dit niet lukt, zodat teruggevallen kan worden op
     * de normale (SDP-based) methode.
     */
    private fun createFixedChannelServerSocket(adapter: BluetoothAdapter): BluetoothServerSocket? {
        return try {
            val method = adapter.javaClass.getMethod(
                "listenUsingInsecureRfcommOn", Int::class.javaPrimitiveType
            )
            method.invoke(adapter, FIXED_RFCOMM_CHANNEL) as? BluetoothServerSocket
        } catch (e: Exception) {
            null
        }
    }
}
