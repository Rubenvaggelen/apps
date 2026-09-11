package com.gmailorg.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

/**
 * Houdt een blijvende Bluetooth-verbinding (RFCOMM) open met de gekoppelde
 * autoradio zolang "WhatsApp naar autoradio" aanstaat. Via dezelfde
 * verbinding worden zowel WhatsApp-meldingen naar de auto gestuurd, als
 * commando's van de auto terug ontvangen (bijv. "spreek een antwoord in").
 */
class CarRadioConnectionService : Service() {

    companion object {
        // Moet exact overeenkomen met BluetoothListenerService.APP_UUID in de carradio-module.
        private val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        running = true
        Thread { connectionLoop() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Autoradio-verbinding", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The One – Autoradio")
            .setContentText("Verbinden met je autoradio...")
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

    private fun connectionLoop() {
        while (running) {
            val address = CarRadioForwarder.selectedDeviceAddress(this)
            if (address == null || !CarRadioForwarder.isEnabled(this)) {
                updateStatus("Niet actief (geen autoradio gekozen of uitgeschakeld)")
                Thread.sleep(3000)
                continue
            }
            var socket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
                updateStatus("Verbinden met ${CarRadioForwarder.selectedDeviceName(this) ?: address}...")
                // Voorkomt een bekend Bluetooth-probleem: als er nog een scan
                // (discovery) loopt, kan connect() daardoor mislukken of vasthangen.
                try { adapter.cancelDiscovery() } catch (e: SecurityException) { /* geen toestemming, negeren */ }

                val device = adapter.getRemoteDevice(address)
                socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                outputStream = socket.outputStream
                updateStatus("Verbonden met ${CarRadioForwarder.selectedDeviceName(this) ?: address}")

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                var line: String?
                while (running) {
                    line = reader.readLine() ?: break
                    if (line.trim() == CMD_REPLY_REQUEST) {
                        handleReplyRequest()
                    }
                }
                updateStatus("Verbinding verbroken — opnieuw proberen...")
            } catch (e: SecurityException) {
                Log.w(TAG, "Geen Bluetooth-toestemming", e)
                updateStatus("⚠️ Geen Bluetooth-toestemming — zet dit in Instellingen nogmaals aan.")
            } catch (e: Exception) {
                Log.w(TAG, "Autoradio-verbinding niet beschikbaar, opnieuw proberen", e)
                updateStatus("⚠️ Kan geen verbinding maken (${e.javaClass.simpleName}) — opnieuw proberen...")
            } finally {
                outputStream = null
                try { socket?.close() } catch (e: Exception) { /* negeren */ }
            }
            Thread.sleep(4000)
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
                    sendMessage("STATUS:Kon je antwoord niet verstaan, probeer opnieuw.")
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
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
