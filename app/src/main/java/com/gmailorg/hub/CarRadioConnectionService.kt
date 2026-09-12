package com.gmailorg.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
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
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

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
    private var serverSocket: BluetoothServerSocket? = null
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
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
                updateStatus("Wacht op verbinding met je autoradio...")
                if (serverSocket == null) {
                    // Eerst proberen zonder SDP (vast kanaal); lukt dat niet
                    // dan terugvallen op de normale SDP-methode.
                    serverSocket = createFixedChannelServerSocket(adapter)
                        ?: adapter.listenUsingRfcommWithServiceRecord("TheOneCarRadio", APP_UUID)
                }
                val socket = serverSocket?.accept() ?: continue
                outputStream = socket.outputStream
                updateStatus("Verbonden met autoradio")
                // Als de RFCOMM-verbinding lukt, weten we zeker dat de auto
                // in bereik is — ook als het aparte ACL-signaal (dat normaal
                // "nearby" bijhoudt) om wat voor reden geen nieuwe gebeurtenis
                // heeft afgevuurd (bv. omdat de Bluetooth-verbinding al vóór
                // een app-update/herstart actief was).
                CarRadioForwarder.setNearby(this, true)

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                var line: String?
                while (running) {
                    line = reader.readLine() ?: break
                    if (line.trim() == CMD_REPLY_REQUEST) {
                        handleReplyRequest()
                    }
                }
                outputStream = null
                try { socket.close() } catch (e: Exception) { /* negeren */ }
                // Altijd een verse serverSocket opbouwen voor de volgende
                // verbinding — hergebruik van dezelfde BluetoothServerSocket
                // na een sessie bleek af en toe onbetrouwbaar (wisselend
                // wel/niet verbinden, zonder duidelijk patroon).
                try { serverSocket?.close() } catch (e: Exception) { /* negeren */ }
                serverSocket = null
                updateStatus("Verbinding verbroken — wachten op nieuwe verbinding...")
            } catch (e: SecurityException) {
                Log.w(TAG, "Geen Bluetooth-toestemming", e)
                updateStatus("⚠️ Geen Bluetooth-toestemming — zet dit in Instellingen nogmaals aan.")
                Thread.sleep(3000)
            } catch (e: Exception) {
                Log.w(TAG, "Autoradio-verbinding niet beschikbaar, opnieuw proberen", e)
                updateStatus("⚠️ Kan geen verbinding maken (${e.javaClass.simpleName}) — opnieuw proberen...")
                try { serverSocket?.close() } catch (ignored: Exception) { /* negeren */ }
                serverSocket = null
                Thread.sleep(5000)
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

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle) {
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
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
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            recognizer.startListening(intent)
        }
    }

    override fun onDestroy() {
        running = false
        outputStream = null
        try { serverSocket?.close() } catch (e: Exception) { /* negeren */ }
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
