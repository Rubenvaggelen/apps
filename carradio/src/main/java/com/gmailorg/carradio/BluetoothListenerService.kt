package com.gmailorg.carradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

/**
 * Luistert continu naar binnenkomende Bluetooth-verbindingen (RFCOMM) op een
 * vaste UUID — dezelfde UUID die "The One" op de telefoon gebruikt om
 * WhatsApp-meldingen te versturen. Elke regel tekst die binnenkomt wordt
 * doorgegeven aan MessageBus, zodat MainActivity 'm kan tonen.
 */
class BluetoothListenerService : Service() {

    companion object {
        // Vaste, eigen UUID voor deze koppeling — moet exact overeenkomen met
        // de UUID die de telefoon-app gebruikt.
        val APP_UUID: UUID = UUID.fromString("8ab8c3d0-6b3e-4a7a-9e77-2f6a2f6d9b10")
        // Vast RFCOMM-kanaalnummer, gebruikt om SDP-registratie te omzeilen.
        // Moet exact overeenkomen met FIXED_RFCOMM_CHANNEL in
        // CarRadioConnectionService.kt op de telefoon.
        private const val FIXED_RFCOMM_CHANNEL = 8
        private const val CHANNEL_ID = "car_radio_service"
        private const val NOTIFICATION_ID = 1
        private const val CMD_REPLY_REQUEST = "REPLY_REQUEST"

        @Volatile
        private var activeOutputStream: OutputStream? = null

        /** Vraagt de telefoon om het laatste WhatsApp-bericht via spraak te beantwoorden. */
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
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        startListening()
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
            .setContentText("Wacht op WhatsApp-meldingen vanaf je telefoon")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startListening() {
        running = true
        Thread {
            while (running) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Thread
                    MessageBus.postStatus("Wachten op verbinding met je telefoon...")
                    // Eén keer registreren en hergebruiken voor elke volgende
                    // verbinding. Steeds opnieuw listenUsingRfcommWithServiceRecord()
                    // aanroepen (bij elke retry) registreert elke keer een nieuwe
                    // SDP-service met dezelfde UUID — op sommige (vooral
                    // goedkopere) Android-hoofdunits raakt de Bluetooth-stack
                    // daardoor in de war, wat zich uit als IOException "Error: -1".
                    if (serverSocket == null) {
                        // Eerst proberen zonder SDP (vast kanaal) — dit omzeilt
                        // de SDP-registratie die op deze hoofdunit blijkbaar
                        // "Error: -1" veroorzaakt. Lukt dat niet (bv. op een
                        // ander toestel waar deze verborgen methode niet
                        // bestaat), dan terugvallen op de normale SDP-methode.
                        serverSocket = createFixedChannelServerSocket(adapter)
                            ?: adapter.listenUsingRfcommWithServiceRecord("TheOneCarRadio", APP_UUID)
                    }
                    val socket = serverSocket?.accept() ?: continue

                    MessageBus.postStatus("Verbonden — WhatsApp-meldingen worden getoond")
                    activeOutputStream = socket.outputStream
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
                    socket.close()
                    MessageBus.postStatus("Verbinding verbroken — wachten op nieuwe verbinding...")
                    // serverSocket blijft bestaan en wordt hergebruikt voor de
                    // volgende accept() — geen nieuwe registratie nodig.
                } catch (e: SecurityException) {
                    MessageBus.postStatus("⚠️ Geen Bluetooth-toestemming — geef 'The One – Autoradio' toestemming in de systeeminstellingen van de auto.")
                    Thread.sleep(3000)
                } catch (e: Exception) {
                    MessageBus.postStatus("⚠️ Verbindingsfout (${e.javaClass.simpleName}: ${e.message}) — opnieuw proberen...")
                    // Bij een echte fout (in tegenstelling tot een normaal
                    // verbroken verbinding) sluiten we de serverSocket alsnog en
                    // wachten we langer, zodat de Bluetooth-stack van de
                    // hoofdunit de tijd krijgt om de oude registratie echt los
                    // te laten voordat we het opnieuw proberen.
                    try { serverSocket?.close() } catch (ignored: Exception) { /* negeren */ }
                    serverSocket = null
                    Thread.sleep(5000)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        running = false
        activeOutputStream = null
        try { serverSocket?.close() } catch (e: Exception) { /* negeren */ }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Maakt een BluetoothServerSocket op een vast kanaalnummer, zonder SDP-
     * registratie — via reflectie, omdat deze methode niet in de publieke
     * Android-SDK zit maar wel bestaat in de onderliggende implementatie.
     * Geeft null terug als dit om wat voor reden niet lukt, zodat er dan
     * teruggevallen kan worden op de normale (SDP-based) methode.
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
