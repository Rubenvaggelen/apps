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
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

/**
 * Verbindt actief met de telefoon (RFCOMM) — de autoradio is de "client",
 * de telefoon is de "server" (luistert). Dit is bewust omgedraaid t.o.v. de
 * eerdere opzet: de Bluetooth-stack van deze hoofdunit bleek geen inkomende
 * verbindingen te kunnen aannemen (IOException "Error: -1", ook niet met een
 * vast kanaalnummer in plaats van SDP). Hoofdunits zoals deze kunnen wél
 * doorgaans zelf verbindingen initiëren — dat gebeurt immers al voor
 * bellen/audio — dus die rol is nu hier belegd.
 */
class BluetoothListenerService : Service() {

    companion object {
        // Moet exact overeenkomen met CarRadioConnectionService.APP_UUID op de telefoon.
        val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
        // Vast RFCOMM-kanaalnummer (omzeilt SDP) — moet exact overeenkomen
        // met FIXED_RFCOMM_CHANNEL in CarRadioConnectionService.kt op de telefoon.
        private const val FIXED_RFCOMM_CHANNEL = 8
        private const val CHANNEL_ID = "car_radio_service"
        private const val NOTIFICATION_ID = 1
        private const val CMD_REPLY_REQUEST = "REPLY_REQUEST"
        private const val CMD_REPLY_TEXT_PREFIX = "REPLY_TEXT:"
        private const val HANDSHAKE_RADIO = "THE_ONE_RADIO_HELLO_V1"
        private const val HANDSHAKE_PHONE = "THE_ONE_PHONE_OK_V1"
        private const val HANDSHAKE_TIMEOUT_MS = 3000L

        @Volatile
        private var activeOutputStream: OutputStream? = null

        /**
         * Oude fallback: laat de telefoon zelf luisteren. Wordt alleen gebruikt
         * als de autoradio geen lokale SpeechRecognizer beschikbaar heeft.
         */
        fun requestVoiceReply(): Boolean {
            val out = activeOutputStream ?: return false
            return try {
                out.write("$CMD_REPLY_REQUEST\n".toByteArray())
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Stuurt door de autoradio herkende tekst naar de telefoon. Base64 voorkomt
         * dat leestekens of eventuele nieuwe regels het eenvoudige regelprotocol breken.
         */
        fun sendVoiceReply(text: String): Boolean {
            if (text.isBlank()) return false
            val out = activeOutputStream ?: return false
            return try {
                val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                out.write("$CMD_REPLY_TEXT_PREFIX$encoded\n".toByteArray(Charsets.UTF_8))
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private var running = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        startConnecting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Autoradio-koppeling", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The One – Autoradio")
            .setContentText("Verbinden met je telefoon...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startConnecting() {
        running = true
        Thread {
            while (running) {
                val address = PairedPhoneStore.selectedAddress(this)
                if (address == null) {
                    MessageBus.postStatus("⚠️ Nog geen telefoon gekozen — tik op \"Kies telefoon\".")
                    Thread.sleep(3000)
                    continue
                }
                var socket: BluetoothSocket? = null
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
                    MessageBus.postStatus("Verbinden met ${PairedPhoneStore.selectedName(this) ?: address}...")
                    try { adapter.cancelDiscovery() } catch (e: SecurityException) { /* geen toestemming, negeren */ }

                    val device = adapter.getRemoteDevice(address)
                    MessageBus.postStatus("The One-verbinding controleren...")
                    socket = connectVerifiedSocket(device)
                    activeOutputStream = socket.outputStream
                    MessageBus.postStatus("Verbonden — WhatsApp-meldingen worden getoond")

                    val reader = BufferedReader(InputStreamReader(socket.inputStream))
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        if (line.startsWith("STATUS:")) {
                            MessageBus.postMessage("✅ " + line.removePrefix("STATUS:"))
                        } else {
                            MessageBus.postMessage(line)
                        }
                    }
                    activeOutputStream = null
                    MessageBus.postStatus("Verbinding verbroken — opnieuw proberen...")
                } catch (e: SecurityException) {
                    MessageBus.postStatus("⚠️ Geen Bluetooth-toestemming — geef 'The One – Autoradio' toestemming in de systeeminstellingen van de auto.")
                    Thread.sleep(3000)
                } catch (e: Exception) {
                    MessageBus.postStatus("⚠️ Verbindingsfout (${e.javaClass.simpleName}: ${e.message}) — opnieuw proberen...")
                    Thread.sleep(4000)
                } finally {
                    activeOutputStream = null
                    try { socket?.close() } catch (e: Exception) { /* negeren */ }
                }
                Thread.sleep(2000)
            }
        }.start()
    }

    /**
     * Verbindt eerst via de normale app-UUID. Alleen als dat niet lukt wordt
     * het oude vaste RFCOMM-kanaal geprobeerd. Een verbinding telt pas als
     * geldig nadat de telefoon onze The One-handshake heeft bevestigd.
     * Daardoor kan een ander Bluetooth-profiel op kanaal 8 nooit meer een
     * valse "Verbonden"-status veroorzaken.
     */
    private fun connectVerifiedSocket(device: BluetoothDevice): BluetoothSocket {
        var lastError: Exception? = null

        val attempts = listOf<(BluetoothDevice) -> BluetoothSocket?>(
            { d -> d.createRfcommSocketToServiceRecord(APP_UUID) },
            { d -> createFixedChannelSocket(d) }
        )

        for (createSocket in attempts) {
            var candidate: BluetoothSocket? = null
            try {
                candidate = createSocket(device) ?: continue
                candidate.connect()

                val out = candidate.outputStream
                val input = candidate.inputStream
                out.write("$HANDSHAKE_RADIO\n".toByteArray(Charsets.UTF_8))
                out.flush()

                val ack = readLineWithTimeout(input, HANDSHAKE_TIMEOUT_MS)
                if (ack != HANDSHAKE_PHONE) {
                    throw IOException("Geen geldige The One-handshake")
                }
                return candidate
            } catch (e: Exception) {
                lastError = e
                try { candidate?.close() } catch (_: Exception) { }
            }
        }

        throw lastError ?: IOException("Geen The One Bluetooth-verbinding beschikbaar")
    }

    private fun readLineWithTimeout(input: InputStream, timeoutMs: Long): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val builder = StringBuilder()
        while (running && SystemClock.elapsedRealtime() < deadline) {
            if (input.available() > 0) {
                val value = input.read()
                if (value == -1) return null
                when (value.toChar()) {
                    '\n' -> return builder.toString().trim()
                    '\r' -> Unit
                    else -> {
                        if (builder.length >= 512) return null
                        builder.append(value.toChar())
                    }
                }
            } else {
                Thread.sleep(20)
            }
        }
        return null
    }

    override fun onDestroy() {
        running = false
        activeOutputStream = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Maakt een BluetoothSocket op een vast kanaalnummer, zonder SDP-opzoek
     * — via reflectie, omdat deze methode niet in de publieke Android-SDK
     * zit maar wel bestaat in de onderliggende implementatie. Geeft null
     * terug als dit niet lukt, zodat teruggevallen kan worden op de normale
     * (SDP-based) methode.
     */
    private fun createFixedChannelSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod(
                "createInsecureRfcommSocket", Int::class.javaPrimitiveType
            )
            method.invoke(device, FIXED_RFCOMM_CHANNEL) as? BluetoothSocket
        } catch (e: Exception) {
            null
        }
    }
}
