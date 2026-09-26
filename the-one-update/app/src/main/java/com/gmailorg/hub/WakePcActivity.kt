package com.gmailorg.hub

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class WakePcActivity : AppCompatActivity() {

    companion object {
        private const val LAPTOP_NAME = "Ruben"
        private const val LAPTOP_WIFI_MAC = "04-EC-D8-E5-C7-3E"
        private const val HOME_BROADCAST = "192.168.178.255"
        private const val WOL_PORT = 9
        private const val WAKE_PIN = "1306"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake_pc)
        MenuButtonHelper.attach(this)

        val status = findViewById<TextView>(R.id.wakePcStatus)
        val wakeButton = findViewById<View>(R.id.wakePcButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        wakeButton.setOnClickListener {
            askForPinAndWake(status, wakeButton)
        }
    }

    private fun askForPinAndWake(status: TextView, wakeButton: View) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Pincode"
            isSingleLine = true
            setPadding(28, 12, 28, 12)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Laptop wakker maken")
            .setMessage("Voer je pincode in om Ruben uit slaapstand te halen.")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Wakker maken", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() != WAKE_PIN) {
                    input.error = "Onjuiste pincode"
                    return@setOnClickListener
                }

                dialog.dismiss()
                wakeButton.isEnabled = false
                status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                status.text = "Wake-signaal versturen…"

                Thread {
                    val result = runCatching { sendMagicPacket() }
                    runOnUiThread {
                        wakeButton.isEnabled = true
                        if (result.isSuccess) {
                            status.setTextColor(ContextCompat.getColor(this, R.color.amber))
                            status.text =
                                "Wake-signaal naar $LAPTOP_NAME verstuurd. Geef de laptop ongeveer 10–30 seconden."
                            Toast.makeText(this, "Laptop wordt wakker gemaakt", Toast.LENGTH_SHORT).show()
                        } else {
                            status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                            status.text =
                                "Wake-signaal mislukt: ${result.exceptionOrNull()?.message ?: "onbekende fout"}"
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun sendMagicPacket() {
        val mac = parseMac(LAPTOP_WIFI_MAC)
        val payload = ByteArray(6 + 16 * mac.size)

        for (i in 0 until 6) payload[i] = 0xFF.toByte()
        for (i in 6 until payload.size) {
            payload[i] = mac[(i - 6) % mac.size]
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("the-one-wol").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            val targets = linkedSetOf<String>()
            dynamicBroadcast(wifi)?.let { targets.add(it) }
            targets.add(HOME_BROADCAST)
            targets.add("255.255.255.255")

            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(3) {
                    for (target in targets) {
                        val packet = DatagramPacket(
                            payload,
                            payload.size,
                            InetAddress.getByName(target),
                            WOL_PORT
                        )
                        socket.send(packet)
                    }
                    Thread.sleep(120)
                }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun dynamicBroadcast(wifi: WifiManager): String? {
        val dhcp = wifi.dhcpInfo ?: return null
        val ip = dhcp.ipAddress
        val mask = dhcp.netmask
        if (ip == 0 || mask == 0) return null

        val broadcast = (ip and mask) or mask.inv()
        return listOf(
            broadcast and 0xff,
            broadcast shr 8 and 0xff,
            broadcast shr 16 and 0xff,
            broadcast shr 24 and 0xff
        ).joinToString(".")
    }

    private fun parseMac(value: String): ByteArray {
        val parts = value.split("-", ":")
        require(parts.size == 6) { "Ongeldig MAC-adres" }
        return ByteArray(6) { index -> parts[index].toInt(16).toByte() }
    }
}
