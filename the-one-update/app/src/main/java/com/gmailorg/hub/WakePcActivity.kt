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
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class WakePcActivity : AppCompatActivity() {

    companion object {
        private const val LAPTOP_NAME = "Ruben"
        private const val LAPTOP_WIFI_MAC = "04-EC-D8-E5-C7-3E"
        private const val HOME_BROADCAST = "192.168.178.255"
        private const val WOL_PORT = 9
        private const val HOME_PUBLIC_IPV4 = "213.93.2.233"
        private const val HOME_PUBLIC_PORT = 40009
        private const val PIN_SALT_HEX = "031507ef415e3d21765f1fcc73740631"
        private const val PIN_PBKDF2_HEX = "57eb95e2c96251b01c5447420126c61d9cccbec1efdc0c392141412bb9c0ad82"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val MAX_FAILED_ATTEMPTS = 3
        private const val LOCKOUT_MS = 5 * 60 * 1000L
    }

    private val securityPrefs by lazy {
        getSharedPreferences("wake_pc_security", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake_pc)
        MenuButtonHelper.attach(this)

        val status = findViewById<TextView>(R.id.wakePcStatus)
        val wakeButton = findViewById<View>(R.id.wakePcButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        wakeButton.setOnClickListener {
            val lockedUntil = securityPrefs.getLong("locked_until", 0L)
            val now = System.currentTimeMillis()
            if (lockedUntil > now) {
                val seconds = ((lockedUntil - now + 999) / 1000).coerceAtLeast(1)
                status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                status.text = "Te veel foutieve pogingen. Probeer over ongeveer $seconds seconden opnieuw."
                return@setOnClickListener
            }

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
                val lockedUntil = securityPrefs.getLong("locked_until", 0L)
                val now = System.currentTimeMillis()
                if (lockedUntil > now) {
                    val seconds = ((lockedUntil - now + 999) / 1000).coerceAtLeast(1)
                    input.error = "Geblokkeerd. Probeer over ongeveer $seconds seconden opnieuw."
                    return@setOnClickListener
                }

                if (!verifyPin(input.text.toString())) {
                    val attempts = securityPrefs.getInt("failed_attempts", 0) + 1
                    if (attempts >= MAX_FAILED_ATTEMPTS) {
                        securityPrefs.edit()
                            .putInt("failed_attempts", 0)
                            .putLong("locked_until", now + LOCKOUT_MS)
                            .apply()
                        input.error = "Te veel foutieve pogingen. 5 minuten geblokkeerd."
                    } else {
                        securityPrefs.edit().putInt("failed_attempts", attempts).apply()
                        input.error = "Onjuiste pincode. Nog ${MAX_FAILED_ATTEMPTS - attempts} poging(en)."
                    }
                    input.text.clear()
                    return@setOnClickListener
                }

                securityPrefs.edit()
                    .putInt("failed_attempts", 0)
                    .putLong("locked_until", 0L)
                    .apply()

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
            runCatching { acquire() }
        }

        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(3) {
                    val localTargets = linkedSetOf<String>()
                    dynamicBroadcast(wifi)?.let { localTargets.add(it) }
                    localTargets.add(HOME_BROADCAST)
                    localTargets.add("255.255.255.255")

                    for (target in localTargets) {
                        runCatching {
                            socket.send(
                                DatagramPacket(
                                    payload,
                                    payload.size,
                                    InetAddress.getByName(target),
                                    WOL_PORT
                                )
                            )
                        }
                    }

                    // Voor 5G / buitenhuis. De Ziggo-router moet extern UDP
                    // HOME_PUBLIC_PORT doorsturen naar 192.168.178.193:9.
                    runCatching {
                        socket.send(
                            DatagramPacket(
                                payload,
                                payload.size,
                                InetAddress.getByName(HOME_PUBLIC_IPV4),
                                HOME_PUBLIC_PORT
                            )
                        )
                    }

                    Thread.sleep(120)
                }
            }
        } finally {
            if (lock.isHeld) runCatching { lock.release() }
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

    private fun verifyPin(value: String): Boolean {
        val salt = PIN_SALT_HEX.hexToBytes()
        val spec = PBEKeySpec(value.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
        spec.clearPassword()

        val expected = PIN_PBKDF2_HEX.hexToBytes()
        if (derived.size != expected.size) return false

        var diff = 0
        for (i in derived.indices) {
            diff = diff or (derived[i].toInt() xor expected[i].toInt())
        }
        return diff == 0
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun parseMac(value: String): ByteArray {
        val parts = value.split("-", ":")
        require(parts.size == 6) { "Ongeldig MAC-adres" }
        return ByteArray(6) { index -> parts[index].toInt(16).toByte() }
    }
}
