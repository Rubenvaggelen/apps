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
    private var activeAccessDialog: AlertDialog? = null

    private data class WakeTarget(
        val id: String,
        val label: String,
        val mac: String,
        val lanIp: String,
        val saltHex: String,
        val passwordHashHex: String,
        val tailscaleHost: String? = null,
        val externalPort: Int? = null
    )

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
        private const val PIN_PBKDF2_HEX = "b0caff5d9cc87fbe048db6f429b51da1a69bb3abcbdd8955d1f3290cd096a42e"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val MAX_FAILED_ATTEMPTS = 3
        private const val LOCKOUT_MS = 5 * 60 * 1000L

        private val WAKE_TARGETS = listOf(
            WakeTarget(
                id = "surface",
                label = "Surface",
                mac = "BC-83-85-DB-DE-FC",
                lanIp = "192.168.178.154",
                saltHex = "06ebab52278f7e32f009df4fc5b4ede4",
                passwordHashHex = "84bb1659722c537446ce0ded9fb367955de41b087f0fe109e3aad3543a7ed8b8",
                externalPort = HOME_PUBLIC_PORT
            ),
            WakeTarget(
                id = "ruben",
                label = "Ruben",
                mac = LAPTOP_WIFI_MAC,
                lanIp = LAPTOP_LAN_IP,
                saltHex = "b020f7d1db4c107b6a3f50cbd1309bb0",
                passwordHashHex = "d005b2a5de856f2c1df8898b23a97564010c6ebcd5f59c26e435218e97b79b3c",
                tailscaleHost = LAPTOP_TAILSCALE_HOST,
                externalPort = HOME_PUBLIC_PORT
            ),
            WakeTarget(
                id = "hub",
                label = "THEONE-HUB",
                mac = "B4-A9-FC-64-25-CE",
                lanIp = "192.168.178.183",
                saltHex = "4fed35ad7dc4722cae539e2404726836",
                passwordHashHex = "2107e3ebfd5d70d5ee41911047caf04bdcb06f63afcdbaebe48b24132cb91e9e",
                externalPort = HOME_PUBLIC_PORT
            )
        )
    }

    private val securityPrefs by lazy {
        getSharedPreferences("wake_pc_security", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake_pc)
        MenuButtonHelper.attach(this)

        // PIN-migratie: wis een oude lockout éénmalig wanneer de bedienings-PIN wijzigt.
        if (securityPrefs.getInt("control_pin_version", 0) < 3) {
            securityPrefs.edit()
                .putInt("control_pin_version", 3)
                .putInt("failed_attempts", 0)
                .putLong("locked_until", 0L)
                .apply()
        }

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
                handleAccessManagementIntent(manageDevicesButton)
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

            chooseWakeDevice(status, wakeButton)
        }

        sleepButton.setOnClickListener {
            val lockedUntil = securityPrefs.getLong("locked_until", 0L)
            val now = System.currentTimeMillis()
            if (lockedUntil > now) {
                val seconds = ((lockedUntil - now + 999) / 1000).coerceAtLeast(1)
                status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                status.text = "Te veel foutieve pogingen. Probeer over ongeveer $seconds seconden opnieuw."
                return@setOnClickListener
            }
            chooseSleepDevice(status, sleepButton)
        }

        remoteButton.setOnClickListener {
            openRemoteControl()
        }

        manageDevicesButton.setOnClickListener {
            openDeviceManager(manageDevicesButton)
        }
    }

    override fun onNewIntent(incoming: Intent) {
        super.onNewIntent(incoming)
        setIntent(incoming)
        if (incoming.getBooleanExtra("open_access_management", false)) {
            activeAccessDialog?.dismiss()
            handleAccessManagementIntent(findViewById(R.id.manageDevicesButton))
        }
    }

    private fun handleAccessManagementIntent(trigger: View) {
        if (!intent.getBooleanExtra("open_access_management", false)) return
        intent.removeExtra("open_access_management")
        val groupKey = intent.getStringExtra("focus_access_group").orEmpty()
        val oldDeviceId = intent.getStringExtra("focus_device_id").orEmpty()
        intent.removeExtra("focus_access_group")
        intent.removeExtra("focus_device_id")
        val focusKey = groupKey.ifBlank { oldDeviceId }
        openDeviceManager(trigger, focusKey)
    }

    private fun requestDeviceLabel(raw: String): String {
        val inside = Regex("\\((.*)\\)$").find(raw)?.groupValues?.getOrNull(1) ?: raw
        return inside.substringAfter("•", inside).trim()
    }

    private fun withOwnerAdminPin(onPinReady: (String) -> Unit) {
        if (activeDeviceAdminPin.isNotBlank()) {
            onPinReady(activeDeviceAdminPin)
            return
        }
        val input = EditText(this).apply {
            hint = "Main-beheerderspincode"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Eigenaar bevestigen")
            .setMessage("Voer de Main-beheerderspincode in om wijzigingen in personen en apprechten goed te keuren.")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Doorgaan", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = input.text.toString().trim()
                if (candidate.isBlank()) {
                    input.error = "Vul de beheerderspincode in"
                    return@setOnClickListener
                }
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positive.isEnabled = false
                Thread {
                    val valid = runCatching { MainDeviceRegistry.ownerStatus(this@WakePcActivity, candidate) }.getOrDefault(false)
                    runOnUiThread {
                        if (valid) {
                            activeDeviceAdminPin = candidate
                            dialog.dismiss()
                            onPinReady(candidate)
                        } else {
                            input.text?.clear()
                            input.error = "De beheerderspincode klopt niet"
                            positive.isEnabled = true
                        }
                    }
                }.start()
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun openDeviceManager(trigger: View, focusKey: String = "") {
        trigger.isEnabled = false
        Thread {
            val owner = MainDeviceRegistry.isLocallyOwner(this)
            val devices = if (owner) runCatching {
                MainDeviceRegistry.listDevices(this, "")
            }.getOrNull() else null

            runOnUiThread {
                trigger.isEnabled = true
                if (!owner) {
                    Toast.makeText(
                        this,
                        "Alleen het eigenaarstoestel mag personen en apprechten beheren.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                if (devices == null) {
                    Toast.makeText(
                        this,
                        "Personen en apprechten konden niet worden geladen.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                showManagedDevices(devices, focusKey)
            }
        }.start()
    }

    private data class ManagedAppRight(
        val label: String,
        val scope: String,
        val allowed: Boolean,
        val pending: Boolean
    )

    private fun managedAppRights(device: MainRegisteredDevice): List<ManagedAppRight> = listOf(
        ManagedAppRight("The One", MainDeviceRegistry.ACCESS_THE_ONE_USER, device.theOneUserRights, false),
        ManagedAppRight("The One Cinema", MainDeviceRegistry.ACCESS_CINEMA_ANDROID, device.cinemaAndroidRights, false),
        ManagedAppRight("The One Series", MainDeviceRegistry.ACCESS_SERIES_ANDROID, device.seriesAndroidRights, false),
        ManagedAppRight("The One TV", MainDeviceRegistry.ACCESS_TV_ANDROID, device.tvAndroidRights, false),
        ManagedAppRight("The One Kids", MainDeviceRegistry.ACCESS_KIDS_ANDROID, device.kidsAndroidRights, device.pendingKidsAndroid),
        ManagedAppRight("Media Player", MainDeviceRegistry.ACCESS_MEDIA_PLAYER, device.mediaPlayerRights, device.pendingMediaPlayer),
        ManagedAppRight("Media Player XXX", MainDeviceRegistry.ACCESS_MEDIA_PLAYER_XXX, device.mediaPlayerXxxRights, device.pendingMediaPlayerXxx),
        ManagedAppRight("The One Mixes", MainDeviceRegistry.ACCESS_MIXES, device.mixesRights, device.pendingMixes),
        ManagedAppRight("The One Shared Media", MainDeviceRegistry.ACCESS_SHARED, device.sharedRights, device.pendingShared),
        ManagedAppRight("Favorites", MainDeviceRegistry.ACCESS_FAVORITES, device.favoritesRights, device.pendingFavorites),
        ManagedAppRight("The One DJ import", MainDeviceRegistry.ACCESS_DJ, device.djRights, device.pendingDj),
        ManagedAppRight("The One Run", MainDeviceRegistry.ACCESS_RUN, device.runRights, device.pendingRun),
        ManagedAppRight("Muziek organiseren", MainDeviceRegistry.ACCESS_ORGANIZE, device.downloadsRights, device.pendingDownloads),
        ManagedAppRight("Bestanden downloaden", MainDeviceRegistry.ACCESS_FILE_DOWNLOADS, device.fileDownloadsRights, device.pendingFileDownloads)
    )

    private fun personGroupKey(device: MainRegisteredDevice): String = when {
        device.owner -> "owner|" + device.id
        device.personId.isNotBlank() -> "person|" + device.personId
        device.personName.isNotBlank() -> "name|" + device.personName.trim().replace(Regex("\\s+"), " ").lowercase()
        else -> "device|" + device.id
    }

    private fun performManagedChange(
        focusKey: String,
        failureLabel: String,
        action: (String) -> Unit
    ) {
        withOwnerAdminPin { pin ->
            Thread {
                val result = runCatching { action(pin) }
                runOnUiThread {
                    result.onSuccess {
                        AccessRequestNotificationWorker.checkNow(this)
                        activeAccessDialog?.dismiss()
                        openDeviceManager(findViewById(R.id.manageDevicesButton), focusKey)
                        Toast.makeText(this, "Wijziging verwerkt.", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        activeDeviceAdminPin = ""
                        AccessRequestNotificationWorker.checkNow(this)
                        activeAccessDialog?.dismiss()
                        openDeviceManager(findViewById(R.id.manageDevicesButton), focusKey)
                        Toast.makeText(
                            this,
                            "$failureLabel: " + (it.message ?: "onbekende fout"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }

    private fun confirmRemoveManagedApp(
        personName: String,
        personId: String,
        deviceIds: List<String>,
        scope: String,
        appLabel: String,
        focusKey: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("Apprecht verwijderen")
            .setMessage("Verwijder $appLabel voor $personName? Dit trekt de toegang in en haalt een eventuele aanvraag weg.")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Verwijderen") { _, _ ->
                performManagedChange(focusKey, "Apprecht verwijderen mislukt") { pin ->
                    MainDeviceRegistry.removePersonApp(this, pin, personId, deviceIds, scope)
                }
            }
            .show()
    }

    private fun confirmRemovePerson(
        personName: String,
        personId: String,
        deviceIds: List<String>,
        focusKey: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("Persoon verwijderen")
            .setMessage("Verwijder $personName en alle gekoppelde appregistraties, rechten en openstaande aanvragen?")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Persoon verwijderen") { _, _ ->
                performManagedChange(focusKey, "Persoon verwijderen mislukt") { pin ->
                    MainDeviceRegistry.removePerson(this, pin, personId, deviceIds)
                }
            }
            .show()
    }

    private fun showManagedDevices(devices: List<MainRegisteredDevice>, focusKey: String = "") {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 8, 18, 8)
        }
        list.addView(TextView(this).apply {
            text = "Alle personen staan hier één keer. Alleen verleende rechten en openstaande aanvragen worden getoond."
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
            setPadding(0, 0, 0, 12)
        })

        val grouped = devices.groupBy(::personGroupKey)
        fun isFocused(entry: Map.Entry<String, List<MainRegisteredDevice>>): Boolean {
            if (focusKey.isBlank()) return false
            if (focusKey.startsWith("person|")) return entry.key == focusKey
            val deviceId = focusKey.removePrefix("device|")
            return entry.value.any { it.id == deviceId } || entry.key == focusKey
        }
        val groups = grouped.entries.sortedWith(
            compareBy<Map.Entry<String, List<MainRegisteredDevice>>>(
                { if (isFocused(it)) 0 else 1 },
                { it.value.firstOrNull()?.personName.orEmpty().lowercase() }
            )
        )

        groups.forEachIndexed { index, entry ->
            val personDevices = entry.value
            val personName = personDevices.map { it.personName.trim() }.firstOrNull { it.isNotBlank() }
                ?: personDevices.firstOrNull()?.name?.let(::requestDeviceLabel)?.ifBlank { "Naam nog niet ingevuld" }
                ?: "Naam nog niet ingevuld"
            val groupKey = entry.key
            val focus = groupKey.ifBlank { focusKey }
            val compactRights = personDevices.flatMap { device ->
                managedAppRights(device).filter { it.allowed || it.pending }.map { device to it }
            }.groupBy { it.second.scope }
            val pendingCount = compactRights.values.sumOf { scopeRows ->
                scopeRows.filter { it.second.pending && !it.second.allowed }.map { it.first.id }.distinct().size
            }
            val summaryParts = mutableListOf<String>()
            if (personDevices.any { it.owner }) {
                summaryParts.add("Eigenaarstoestel")
            } else {
                if (compactRights.isNotEmpty()) summaryParts.add("${compactRights.size} apps")
                if (pendingCount > 0) summaryParts.add("${pendingCount} aanvragen open")
                if (summaryParts.isEmpty()) summaryParts.add("Geen actieve rechten of aanvragen")
            }
            if (personDevices.any { it.blocked }) summaryParts.add("Geblokkeerd")

            val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val initiallyExpanded = isFocused(entry)
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16, 14, 16, 14)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            val headerText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            headerText.addView(TextView(this).apply {
                text = personName + if (personDevices.any { it.owner }) "  •  The One" else ""
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_main))
            })
            headerText.addView(TextView(this).apply {
                text = summaryParts.joinToString(" • ")
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
                setPadding(0, 3, 0, 0)
            })
            header.addView(headerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val chevron = TextView(this).apply {
                text = if (initiallyExpanded) "▴" else "▾"
                textSize = 24f
                setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
                setPadding(12, 0, 0, 0)
                contentDescription = if (initiallyExpanded) "Inklappen" else "Rechten uitklappen"
            }
            header.addView(chevron)
            section.addView(header)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 4, 16, 14)
                visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
            }
            header.setOnClickListener {
                val opening = card.visibility != View.VISIBLE
                card.visibility = if (opening) View.VISIBLE else View.GONE
                chevron.text = if (opening) "▴" else "▾"
                chevron.contentDescription = if (opening) "Inklappen" else "Rechten uitklappen"
            }
            section.addView(card)
            if (personDevices.any { it.blocked }) {
                card.addView(TextView(this).apply {
                    text = "GEBLOKKEERD"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@WakePcActivity, android.R.color.holo_red_light))
                    setPadding(0, 2, 0, 6)
                })
            }

            if (personDevices.any { it.owner }) {
                card.addView(TextView(this).apply {
                    text = "Eigenaarstoestel • alle Main-beheerrechten actief"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.amber))
                })
            } else {
                val rightsByScope = personDevices.flatMap { device ->
                    managedAppRights(device).filter { it.allowed || it.pending }.map { device to it }
                }.groupBy { it.second.scope }
                if (rightsByScope.isEmpty()) {
                    card.addView(TextView(this).apply {
                        text = "Geen actieve rechten of openstaande aanvragen"
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
                        setPadding(0, 4, 0, 8)
                    })
                } else {
                    rightsByScope.values.sortedBy { it.first().second.label.lowercase() }.forEach { scopeRows ->
                        val right = scopeRows.first().second
                        val allowedCount = scopeRows.count { it.second.allowed }
                        val pendingDevices = scopeRows.filter { it.second.pending && !it.second.allowed }
                            .map { it.first }
                            .distinctBy { it.id }
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, 9, 0, 9)
                        }
                        row.addView(TextView(this).apply {
                            text = right.label
                            textSize = 16f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_main))
                        })
                        val statusParts = mutableListOf<String>()
                        if (allowedCount > 0) statusParts.add(
                            if (allowedCount == 1) "Toegestaan" else "Toegestaan op $allowedCount installaties"
                        )
                        if (pendingDevices.isNotEmpty()) statusParts.add(
                            if (pendingDevices.size == 1) "1 aanvraag open" else "${pendingDevices.size} aanvragen open"
                        )
                        row.addView(TextView(this).apply {
                            text = statusParts.joinToString(" • ")
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(
                                this@WakePcActivity,
                                if (pendingDevices.isNotEmpty()) R.color.amber else R.color.text_dim
                            ))
                        })
                        if (pendingDevices.isNotEmpty()) {
                            val pendingActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                            pendingActions.addView(android.widget.Button(this).apply {
                                text = "Toestaan"
                                setOnClickListener {
                                    isEnabled = false
                                    performManagedChange(focus, "Aanvraag goedkeuren mislukt") { pin ->
                                        pendingDevices.forEach { pendingDevice ->
                                            MainDeviceRegistry.setAccessRight(
                                                this@WakePcActivity, pin, pendingDevice.id, right.scope, true
                                            )
                                        }
                                    }
                                }
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            pendingActions.addView(android.widget.Button(this).apply {
                                text = "Weigeren"
                                setOnClickListener {
                                    isEnabled = false
                                    performManagedChange(focus, "Aanvraag weigeren mislukt") { pin ->
                                        pendingDevices.forEach { pendingDevice ->
                                            MainDeviceRegistry.setAccessRight(
                                                this@WakePcActivity, pin, pendingDevice.id, right.scope, false
                                            )
                                        }
                                    }
                                }
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 })
                            row.addView(pendingActions)
                        }
                        if (allowedCount > 0 || pendingDevices.isNotEmpty()) {
                            row.addView(android.widget.Button(this).apply {
                                text = "App verwijderen"
                                setOnClickListener {
                                    confirmRemoveManagedApp(
                                        personName,
                                        personDevices.firstOrNull()?.personId.orEmpty(),
                                        personDevices.map { it.id },
                                        right.scope,
                                        right.label,
                                        focus
                                    )
                                }
                            })
                        }
                        card.addView(row)
                        card.addView(View(this).apply {
                            setBackgroundColor(ContextCompat.getColor(this@WakePcActivity, R.color.line))
                        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
                    }
                }
                if (!personDevices.any { it.owner }) {
                    card.addView(android.widget.Button(this).apply {
                        text = "Persoon verwijderen"
                        setOnClickListener {
                            confirmRemovePerson(
                                personName,
                                personDevices.firstOrNull()?.personId.orEmpty(),
                                personDevices.map { it.id },
                                focus
                            )
                        }
                    })
                }
            }

            list.addView(section)
            if (index < groups.lastIndex) {
                list.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(this@WakePcActivity, R.color.line))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
        if (groups.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nog geen personen geregistreerd."
                setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
                textSize = 14f
            })
        }

        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(list) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Personen en apprechten")
            .setView(scroll)
            .setNegativeButton("Sluiten", null)
            .create()
        activeAccessDialog = dialog
        dialog.setOnDismissListener { if (activeAccessDialog === dialog) activeAccessDialog = null }
        dialog.show()
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

    private fun chooseWakeDevice(status: TextView, wakeButton: View) {
        val labels = WAKE_TARGETS.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Apparaat wakker maken")
            .setItems(labels) { _, which ->
                askForPasswordAndWake(status, wakeButton, WAKE_TARGETS[which])
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun askForPasswordAndWake(status: TextView, wakeButton: View, target: WakeTarget) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Pincode of toegangscode"
            isSingleLine = true
            setPadding(28, 12, 28, 12)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(target.label + " wakker maken")
            .setMessage("Voer de bedieningspincode in om " + target.label + " uit slaapstand te halen.")
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

                if (!verifyWakePassword(target, input.text.toString())) {
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
                    val result = runCatching { sendMagicPacket(target) }
                    runOnUiThread {
                        wakeButton.isEnabled = true
                        if (result.isSuccess) {
                            status.setTextColor(ContextCompat.getColor(this, R.color.amber))
                            status.text =
                                "Wake-signaal naar ${target.label} verstuurd. Geef het apparaat ongeveer 10–30 seconden."
                            Toast.makeText(this, target.label + " wordt wakker gemaakt", Toast.LENGTH_SHORT).show()
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

    private fun chooseSleepDevice(status: TextView, sleepButton: View) {
        val labels = WAKE_TARGETS.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Apparaat in slaapstand")
            .setItems(labels) { _, which ->
                askForPinAndSleep(status, sleepButton, WAKE_TARGETS[which])
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun askForPinAndSleep(status: TextView, sleepButton: View, target: WakeTarget) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Pincode"
            isSingleLine = true
            setPadding(28, 12, 28, 12)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(target.label + " in slaapstand")
            .setMessage("Voer de bedieningspincode in om " + target.label + " in slaapstand te zetten.")
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
                status.text = target.label + " in slaapstand zetten…"

                Thread {
                    val result = runCatching { sendLaptopCommand(target, "SLEEP", pin) }
                    runOnUiThread {
                        sleepButton.isEnabled = true
                        if (result.isSuccess) {
                            status.setTextColor(ContextCompat.getColor(this, R.color.amber))
                            status.text = "Slaapstand naar ${target.label} verstuurd."
                        } else {
                            status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                            status.text = "Geen verbinding met ${target.label}. Controleer of The One Window actief is."
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }

    private fun sendLaptopCommand(target: WakeTarget, command: String, pin: String) {
        val hosts = linkedSetOf(target.lanIp)
        target.tailscaleHost?.takeIf { it.isNotBlank() }?.let { hosts.add(it) }
        var lastError: Throwable? = null

        for (host in hosts) {
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
                    throw IllegalStateException("Onverwacht antwoord van apparaat")
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("Apparaat niet bereikbaar")
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
    private fun sendMagicPacket(target: WakeTarget) {
        val mac = parseMac(target.mac)
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
                    localTargets.add(target.lanIp)

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

                    // Alleen apparaten met een ingestelde externe WOL-port worden
                    // ook buiten het thuisnetwerk gewekt.
                    target.externalPort?.let { port ->
                        runCatching {
                            socket.send(
                                DatagramPacket(
                                    payload,
                                    payload.size,
                                    InetAddress.getByName(HOME_PUBLIC_IPV4),
                                    port
                                )
                            )
                        }
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

    private fun verifyWakePassword(target: WakeTarget, value: String): Boolean {
        val salt = target.saltHex.hexToBytes()
        val spec = PBEKeySpec(value.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
        spec.clearPassword()

        val expected = target.passwordHashHex.hexToBytes()
        if (derived.size != expected.size) return false

        var diff = 0
        for (i in derived.indices) {
            diff = diff or (derived[i].toInt() xor expected[i].toInt())
        }
        return diff == 0
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
