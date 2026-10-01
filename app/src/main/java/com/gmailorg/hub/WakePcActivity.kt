package com.gmailorg.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// The One WOL: PIN-beveiligd wakker maken vanuit The One Main
class WakePcActivity : AppCompatActivity() {

    private var activeDeviceAdminPin = ""

    companion object {
        private const val LAPTOP_NAME = "Ruben"
        private const val LAPTOP_WIFI_MAC = "04-EC-D8-E5-C7-3E"
        private const val HOME_BROADCAST = "192.168.178.255"
        private const val WOL_PORT = 9
        private const val LAPTOP_LAN_IP = "192.168.178.193"
        private const val LAPTOP_TAILSCALE_HOST = "ruben"
        private const val LAPTOP_CONTROL_PORT = 38491
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
        val sleepButton = findViewById<View>(R.id.sleepPcButton)
        val remoteButton = findViewById<View>(R.id.remotePcButton)
        val manageDevicesButton = findViewById<View>(R.id.manageDevicesButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        wakeButton.isEnabled = false
        sleepButton.isEnabled = false
        remoteButton.isEnabled = false
        manageDevicesButton.isEnabled = false
        status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        status.text = "Eigenaarsrechten controleren…"

        Thread {
            runCatching { MainDeviceRegistry.heartbeat(this) }
            val owner = MainDeviceRegistry.isLocallyOwner(this)
            runOnUiThread {
                if (!owner) {
                    Toast.makeText(
                        this,
                        "Alleen het eigenaarstoestel mag laptops beheren.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@runOnUiThread
                }

                wakeButton.isEnabled = true
                sleepButton.isEnabled = true
                remoteButton.isEnabled = true
                manageDevicesButton.isEnabled = true
                status.text = "Klaar"
                if (intent.getBooleanExtra("open_access_management", false)) {
                    intent.removeExtra("open_access_management")
                    promptDeviceManagerPin(manageDevicesButton)
                }
            }
        }.start()

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

        sleepButton.setOnClickListener {
            askForPinAndSleep(status, sleepButton)
        }

        remoteButton.setOnClickListener {
            openRemoteControl()
        }

        manageDevicesButton.setOnClickListener {
            promptDeviceManagerPin(manageDevicesButton)
        }
    }

    private fun promptDeviceManagerPin(trigger: View) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Beheerpincode"
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Apparaten beheren")
            .setMessage("Voer de beheerpincode in.")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Openen", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.isBlank()) { input.error = "Vul de beheerpincode in"; return@setOnClickListener }
                trigger.isEnabled = false
                Thread {
                    val owner = runCatching { MainDeviceRegistry.ownerStatus(this, pin) }.getOrDefault(false)
                    val devices = if (owner) runCatching { MainDeviceRegistry.listDevices(this, pin) }.getOrNull() else null
                    runOnUiThread {
                        trigger.isEnabled = true
                        if (!owner) { input.text.clear(); input.error = "Geen beheerderstoegang"; return@runOnUiThread }
                        if (devices == null) { Toast.makeText(this, "Apparaten konden niet worden geladen.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                        activeDeviceAdminPin = pin
                        dialog.dismiss()
                        showManagedDevices(devices)
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showManagedDevices(devices: List<MainRegisteredDevice>) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 8) }
        if (devices.isEmpty()) list.addView(TextView(this).apply { text = "Nog geen apparaten geregistreerd."; setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim)); textSize = 14f })
        devices.forEach { device ->
            val personLabel = device.personName.ifBlank { "Naam nog niet ingevuld" }
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14) }
            card.addView(TextView(this).apply {
                text = (if (device.online) "●  " else "○  ") + personLabel + (if (device.owner) "  •  The One" else "")
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@WakePcActivity, if (device.online) R.color.amber else R.color.text_main))
            })
            card.addView(TextView(this).apply { text = device.name; textSize = 12f; setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim)) })
            val state = TextView(this).apply {
                text = managedDeviceStateText(device)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@WakePcActivity, if (device.blocked) android.R.color.holo_red_light else R.color.text_dim))
            }
            card.addView(state)
            if (device.owner) {
                card.addView(TextView(this).apply {
                    text = "The One • beheerder • alle mediarechten actief • kan niet worden geblokkeerd"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.amber))
                })
            } else {
                fun addAccessControl(
                    label: String,
                    scope: String,
                    initialAllowed: Boolean,
                    initialPending: Boolean
                ) {
                    var allowed = initialAllowed
                    var pending = initialPending

                    val section = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 10, 0, 6)
                    }
                    val accessState = TextView(this).apply {
                        textSize = 12f
                        setPadding(0, 0, 0, 4)
                    }

                    fun refreshState() {
                        accessState.text = when {
                            allowed -> "$label: toegestaan"
                            pending -> "$label: AANGEVRAAGD"
                            else -> "$label: niet toegestaan"
                        }
                        accessState.setTextColor(
                            ContextCompat.getColor(
                                this@WakePcActivity,
                                if (allowed || pending) R.color.amber else R.color.text_dim
                            )
                        )
                    }
                    refreshState()
                    section.addView(accessState)

                    val buttons = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    val grantButton = android.widget.Button(this).apply {
                        fun refreshLabel() {
                            text = if (allowed) "$label intrekken" else "$label toestaan"
                        }
                        refreshLabel()
                        setOnClickListener {
                            isEnabled = false
                            val enable = !allowed
                            Thread {
                                val result = runCatching {
                                    MainDeviceRegistry.setAccessRight(
                                        this@WakePcActivity,
                                        activeDeviceAdminPin,
                                        device.id,
                                        scope,
                                        enable
                                    )
                                }
                                runOnUiThread {
                                    result.onSuccess {
                                        allowed = enable
                                        pending = false
                                        refreshLabel()
                                        refreshState()
                                        Toast.makeText(
                                            this@WakePcActivity,
                                            if (enable) "$personLabel heeft nu toegang tot $label."
                                            else "$personLabel heeft geen toegang meer tot $label.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        AccessRequestNotificationWorker.checkNow(this@WakePcActivity)
                                        isEnabled = true
                                    }.onFailure {
                                        Toast.makeText(
                                            this@WakePcActivity,
                                            "$label wijzigen mislukt: " + (it.message ?: "onbekende fout"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        isEnabled = true
                                    }
                                }
                            }.start()
                        }
                    }
                    buttons.addView(
                        grantButton,
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    )

                    if (initialPending && !initialAllowed) {
                        buttons.addView(
                            android.widget.Button(this).apply {
                                text = "Weigeren"
                                setOnClickListener {
                                    isEnabled = false
                                    Thread {
                                        val result = runCatching {
                                            MainDeviceRegistry.setAccessRight(
                                                this@WakePcActivity,
                                                activeDeviceAdminPin,
                                                device.id,
                                                scope,
                                                false
                                            )
                                        }
                                        runOnUiThread {
                                            result.onSuccess {
                                                pending = false
                                                refreshState()
                                                text = "Geweigerd"
                                                isEnabled = false
                                                Toast.makeText(
                                                    this@WakePcActivity,
                                                    "$label-aanvraag van $personLabel geweigerd.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                AccessRequestNotificationWorker.checkNow(this@WakePcActivity)
                                            }.onFailure {
                                                Toast.makeText(
                                                    this@WakePcActivity,
                                                    "Weigeren mislukt: " + (it.message ?: "onbekende fout"),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                isEnabled = true
                                            }
                                        }
                                    }.start()
                                }
                            },
                            LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            ).apply { marginStart = 8 }
                        )
                    }

                    section.addView(buttons)
                    card.addView(section)
                }

                addAccessControl(
                    "The One Mixes",
                    MainDeviceRegistry.ACCESS_MIXES,
                    device.mixesRights,
                    device.pendingMixes
                )
                addAccessControl(
                    "Shared Media",
                    MainDeviceRegistry.ACCESS_SHARED,
                    device.sharedRights,
                    device.pendingShared
                )
                addAccessControl(
                    "Favorites",
                    MainDeviceRegistry.ACCESS_FAVORITES,
                    device.favoritesRights,
                    device.pendingFavorites
                )
                addAccessControl(
                    "The One DJ import",
                    MainDeviceRegistry.ACCESS_DJ,
                    device.djRights,
                    device.pendingDj
                )
                addAccessControl(
                    "Muziek organiseren",
                    MainDeviceRegistry.ACCESS_ORGANIZE,
                    device.downloadsRights,
                    device.pendingDownloads
                )
                addAccessControl(
                    "Bestanden downloaden",
                    MainDeviceRegistry.ACCESS_FILE_DOWNLOADS,
                    device.fileDownloadsRights,
                    device.pendingFileDownloads
                )

                card.addView(android.widget.Button(this).apply {
                    text = if (device.blocked) "Deblokkeren" else "Blokkeren"
                    setOnClickListener {
                        isEnabled = false
                        val newBlocked = !device.blocked
                        Thread {
                            val result = runCatching { MainDeviceRegistry.setBlocked(this@WakePcActivity, activeDeviceAdminPin, device.id, newBlocked) }
                            runOnUiThread {
                                result.onSuccess {
                                    Toast.makeText(this@WakePcActivity, personLabel + if (newBlocked) " is geblokkeerd." else " is gedeblokkeerd.", Toast.LENGTH_SHORT).show()
                                    isEnabled = true
                                }.onFailure {
                                    Toast.makeText(this@WakePcActivity, "Wijzigen mislukt: " + (it.message ?: "onbekende fout"), Toast.LENGTH_LONG).show()
                                    isEnabled = true
                                }
                            }
                        }.start()
                    }
                })
            }
            list.addView(card)
            list.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(this@WakePcActivity, R.color.line)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(list) }
        AlertDialog.Builder(this).setTitle("Verbonden apparaten").setView(scroll).setNegativeButton("Sluiten", null).show()
    }

    private fun managedDeviceStateText(device: MainRegisteredDevice): String {
        val prefix = if (device.blocked) "GEBLOKKEERD • " else ""
        val onlineText = if (device.online) "Online" else "Offline"
        val platform = listOf(device.platform, if (device.version.isBlank()) "" else "build " + device.version).filter { it.isNotBlank() }.joinToString(" • ")
        val seen = if (device.lastSeen > 0L) {
            val formatter = java.text.SimpleDateFormat("dd-MM HH:mm", java.util.Locale("nl", "NL"))
            "Laatst gezien " + formatter.format(java.util.Date(device.lastSeen * 1000L))
        } else "Nog niet gezien"
        return prefix + listOf(onlineText, platform, seen).filter { it.isNotBlank() }.joinToString(" • ")
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

    private fun askForPinAndSleep(status: TextView, sleepButton: View) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Pincode"
            isSingleLine = true
            setPadding(28, 12, 28, 12)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Laptop in slaapstand")
            .setMessage("Voer je pincode in om Ruben in slaapstand te zetten.")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Slaapstand", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val now = System.currentTimeMillis()
                val lockedUntil = securityPrefs.getLong("locked_until", 0L)
                if (lockedUntil > now) {
                    val seconds = ((lockedUntil - now + 999) / 1000).coerceAtLeast(1)
                    input.error = "Geblokkeerd. Probeer over ongeveer $seconds seconden opnieuw."
                    return@setOnClickListener
                }

                val pin = input.text.toString()
                if (!verifyPin(pin)) {
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
                sleepButton.isEnabled = false
                status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                status.text = "Laptop in slaapstand zetten…"

                Thread {
                    val result = runCatching { sendLaptopCommand("SLEEP", pin) }
                    runOnUiThread {
                        sleepButton.isEnabled = true
                        if (result.isSuccess) {
                            status.setTextColor(ContextCompat.getColor(this, R.color.amber))
                            status.text = "Slaapstand verstuurd. De laptop gaat nu slapen."
                        } else {
                            status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                            status.text =
                                "Geen verbinding met de laptop. Thuis: controleer wifi. Buitenshuis: zet Tailscale aan op je telefoon."
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    private fun sendLaptopCommand(command: String, pin: String) {
        val targets = listOf(LAPTOP_LAN_IP, LAPTOP_TAILSCALE_HOST)
        var lastError: Throwable? = null

        for (host in targets) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, LAPTOP_CONTROL_PORT), 2500)
                    socket.soTimeout = 3500

                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    writer.write("$command $pin\n")
                    writer.flush()

                    val reply = reader.readLine().orEmpty()
                    if (reply.startsWith("OK ")) return
                    if (reply == "ERR AUTH") throw IllegalStateException("Pincode geweigerd")
                    throw IllegalStateException("Onverwacht antwoord van laptop")
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("Laptop niet bereikbaar")
    }

    private fun openRemoteControl() {
        val packageName = "com.theone.remote"
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.putExtra("surface_id", "461504832")
            launch.putExtra("auto_connect", true)
            startActivity(launch)
            return
        }

        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rustdesk.com/web/")))
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
