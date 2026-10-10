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
        val musicStudioAccessButton = findViewById<View>(R.id.musicStudioAccessButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        wakeButton.isEnabled = false
        sleepButton.isEnabled = false
        remoteButton.isEnabled = false
        manageDevicesButton.isEnabled = false
        musicStudioAccessButton.isEnabled = false
        status.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        status.text = "Eigenaarsrechten controleren…"

        Thread {
            runCatching { MainDeviceRegistry.heartbeat(this) }
            val owner = MainDeviceRegistry.isLocallyOwner(this)
            runOnUiThread {
                if (!owner) {
                    // No administrative controls for non-owners. Allow an
                    // explicit, non-privileged registration test instead.
                    AlertDialog.Builder(this)
                        .setTitle("Aanmelding bij beheerder")
                        .setMessage("Dit apparaat heeft geen beheerrechten. Wil je een aanmeldverzoek naar de eigenaar van The One sturen?")
                        .setPositiveButton("Aanmelding aanvragen", null)
                        .setNegativeButton("Sluiten") { _, _ -> finish() }
                        .setCancelable(false)
                        .create()
                        .also { dialog ->
                            dialog.setOnShowListener {
                                val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                send.setOnClickListener {
                                    send.isEnabled = false
                                    Thread {
                                        val submitted = runCatching {
                                            MainLicenseClient.askForAccess(this)
                                        }
                                        runOnUiThread {
                                            if (submitted.getOrNull()?.pending == true) {
                                                Toast.makeText(this, "Aanmelding verzonden. Wacht op goedkeuring in Main.", Toast.LENGTH_LONG).show()
                                                dialog.dismiss()
                                                finish()
                                            } else {
                                                send.isEnabled = true
                                                Toast.makeText(this, "Aanmelding mislukt. Controleer de internetverbinding en probeer opnieuw.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }.start()
                                }
                            }
                            dialog.show()
                        }
                    return@runOnUiThread
                }

                wakeButton.isEnabled = true
                sleepButton.isEnabled = true
                remoteButton.isEnabled = true
                manageDevicesButton.isEnabled = true
                musicStudioAccessButton.isEnabled = true
                status.text = "Klaar"
                if (intent.getBooleanExtra("open_access_management", false)) {
                    intent.removeExtra("open_access_management")
                    openDeviceManager(manageDevicesButton)
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
        // Alleen de eigenaar ziet deze toegang. De server vereist daarnaast
        // een echte Dev Hub-beheerderssessie; een apparaat-ID is niet voldoende.
        musicStudioAccessButton.setOnClickListener {
            val portal = android.net.Uri.parse("https://dev.rubenvanaggelen.com/studio-licenses.php")
            androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, portal)
        }
    }

    private fun openDeviceManager(trigger: View) {
        trigger.isEnabled = false
        Thread {
            val owner = MainDeviceRegistry.isLocallyOwner(this)
            val devices = if (owner) runCatching {
                MainDeviceRegistry.listDevices(this, "")
            }.getOrNull() else null
            val licenseRequests = if (owner && MainLicenseClient.ownerIsPaired(this)) {
                runCatching { MainLicenseClient.pendingOwnerApprovals(this) }.getOrNull()
            } else emptyList()


            runOnUiThread {
                trigger.isEnabled = true
                if (!owner) {
                    Toast.makeText(
                        this,
                        "Alleen het eigenaarstoestel mag apparaten beheren.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                if (devices == null) {
                    Toast.makeText(
                        this,
                        "Apparaten konden niet worden geladen.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                activeDeviceAdminPin = ""
                showManagedDevices(devices, licenseRequests)
            }
        }.start()
    }

    private fun showManagedDevices(
        devices: List<MainRegisteredDevice>,
        licenseRequests: List<MainPendingLicenseRequest>?
    ) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 8, 18, 8) }

        list.addView(TextView(this).apply {
            text = "Nieuwe Main-aanmeldingen — wachten op goedkeuring"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.amber))
            setPadding(10, 14, 10, 10)
        })

        if (!MainLicenseClient.ownerIsPaired(this)) {
            list.addView(TextView(this).apply {
                text = "Eenmalig koppelen met de beveiligde Dev Hub is nodig om licentieaanvragen veilig in Main Meldingen te ontvangen en hier goed te keuren."
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
            })
            list.addView(android.widget.Button(this).apply {
                text = "Koppel licentiemeldingen"
                setOnClickListener { showOwnerPairingDialog() }
            })
        } else if (licenseRequests == null) {
            list.addView(TextView(this).apply {
                text = "Licentieserver niet bereikbaar. Bestaande meldingen blijven bewaard; probeer later opnieuw."
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@WakePcActivity, android.R.color.holo_red_light))
            })
        } else if (licenseRequests.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Geen nieuwe aanmeldingen die op jouw goedkeuring wachten."
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
            })
        } else {
            licenseRequests.sortedWith(
                compareByDescending<MainPendingLicenseRequest> {
                    it.id == intent.getStringExtra("focus_license_request_id")
                }.thenBy { it.createdAt }
            ).forEach { pending ->
                val entry = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(14, 12, 14, 12)
                }
                entry.addView(TextView(this).apply {
                    text = pending.person.ifBlank { "Onbekende gebruiker" } +
                        " • Aanmelding wacht op toestemming"
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_main))
                })
                entry.addView(TextView(this).apply {
                    text = "Apparaat-ID: " + pending.deviceId + "\nAangevraagd: " + pending.createdAt
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
                })
                entry.addView(android.widget.Button(this).apply {
                    text = "Aanmelding goedkeuren"
                    setOnClickListener {
                        val action = this
                        action.isEnabled = false
                        Thread {
                            val approved = runCatching {
                                MainLicenseClient.approveOwnerRequest(this@WakePcActivity, pending.id)
                            }
                            runOnUiThread {
                                if (approved.getOrNull() == true) {
                                    AccessRequestNotifications.resolveLicense(this@WakePcActivity, pending.id)
                                    Toast.makeText(this@WakePcActivity,
                                        "Aanmelding goedgekeurd. De gebruiker kan nu activeren.",
                                        Toast.LENGTH_LONG).show()
                                    AccessRequestNotificationWorker.checkNow(this@WakePcActivity)
                                    list.removeView(entry)
                                } else {
                                    action.isEnabled = true
                                    Toast.makeText(this@WakePcActivity,
                                        "Goedkeuren mislukt. De aanvraag blijft in je meldingen staan.",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    }
                })
                list.addView(entry)
            }
        }

        list.addView(TextView(this).apply {
            text = "Geregistreerde apparaten en rechten"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.amber))
            setPadding(10, 20, 10, 8)
        })
        if (devices.isEmpty()) list.addView(TextView(this).apply { text = "Nog geen apparaten geregistreerd."; setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim)); textSize = 14f })
        // Group display by person only; preserve each device ID and its own access controls.
        // Empty names must not merge unrelated unregistered devices.
        val personGroups = devices.groupBy { device ->
            device.personName.trim().takeIf { it.isNotEmpty() }?.lowercase(java.util.Locale.ROOT)
                ?: "unknown-device:${device.id}"
        }
        personGroups.values.forEach { personDevices ->
            val groupName = personDevices.first().personName.ifBlank { "Naam nog niet ingevuld" }
            val groupHeader = TextView(this).apply {
                text = "$groupName (${personDevices.size} apparaat${if (personDevices.size == 1) "" else "en"})  ▾"
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.amber))
                setPadding(12, 16, 12, 12)
            }
            val groupDetails = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            groupHeader.setOnClickListener {
                val expanded = groupDetails.visibility != View.VISIBLE
                groupDetails.visibility = if (expanded) View.VISIBLE else View.GONE
                groupHeader.text = "$groupName (${personDevices.size} apparaat${if (personDevices.size == 1) "" else "en"})  " + if (expanded) "▴" else "▾"
            }
            list.addView(groupHeader)
            list.addView(groupDetails)
            personDevices.forEach { device ->
            val personLabel = device.personName.ifBlank { "Naam nog niet ingevuld" }
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14) }
            val expandHeader = TextView(this).apply {
                text = (if (device.online) "●  " else "○  ") + personLabel + (if (device.owner) "  •  The One" else "")
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@WakePcActivity, if (device.online) R.color.amber else R.color.text_main))
            }
            card.addView(expandHeader)
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val collapsedTitle = expandHeader.text.toString()
            expandHeader.text = "$collapsedTitle  ▾"
            expandHeader.setPadding(0, 8, 0, 10)
            expandHeader.setOnClickListener {
                val open = details.visibility != View.VISIBLE
                details.visibility = if (open) View.VISIBLE else View.GONE
                expandHeader.text = "$collapsedTitle  " + if (open) "▴" else "▾"
            }
            details.addView(TextView(this).apply { text = device.name; textSize = 12f; setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim)) })
            val state = TextView(this).apply {
                text = managedDeviceStateText(device)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@WakePcActivity, if (device.blocked) android.R.color.holo_red_light else R.color.text_dim))
            }
            details.addView(state)
            if (device.owner) {
                details.addView(TextView(this).apply {
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
                    details.addView(section)
                }

                addAccessControl(
                    "Media Player",
                    MainDeviceRegistry.ACCESS_MEDIA_PLAYER,
                    device.mediaPlayerRights,
                    device.pendingMediaPlayer
                )
                addAccessControl(
                    "The One Mixes",
                    MainDeviceRegistry.ACCESS_MIXES,
                    device.mixesRights,
                    device.pendingMixes
                )
                addAccessControl(
                    "The One Shared Media",
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
                    "The One Run",
                    MainDeviceRegistry.ACCESS_RUN,
                    device.runRights,
                    device.pendingRun
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

                // Device removal is irreversible: require exact selection and a second
                // confirmation. The paired owner's server credential is mandatory.
                if (MainLicenseClient.ownerIsPaired(this)) {
                    details.addView(android.widget.Button(this).apply {
                        text = "Apparaat verwijderen (ook rechten en licentie)"
                        setOnClickListener {
                            val clicked = this
                            AlertDialog.Builder(this@WakePcActivity)
                                .setTitle("Apparaat definitief verwijderen?")
                                .setMessage(
                                    "Naam: $personLabel\\n" +
                                    "Apparaat: ${device.name}\\n" +
                                    "ID: ${device.id}\\n\\n" +
                                    "Alle toegangsrechten, licenties en openstaande aanvragen " +
                                    "van dit apparaat worden ingetrokken. " +
                                    "Dit apparaat moet zich daarna opnieuw aanmelden."
                                )
                                .setNegativeButton("Annuleren", null)
                                .setPositiveButton("Definitief verwijderen") { _, _ ->
                                    clicked.isEnabled = false
                                    Thread {
                                        val result = runCatching {
                                            MainLicenseClient.removeOwnerDevice(
                                                this@WakePcActivity, device.id, device.registered
                                            )
                                        }
                                        runOnUiThread {
                                            result.onSuccess {
                                                groupDetails.removeView(card)
                                                Toast.makeText(this@WakePcActivity,
                                                    "Apparaat verwijderd; rechten en licentie ingetrokken.",
                                                    Toast.LENGTH_LONG).show()
                                                AccessRequestNotificationWorker.checkNow(this@WakePcActivity)
                                            }.onFailure {
                                                clicked.isEnabled = true
                                                Toast.makeText(this@WakePcActivity,
                                                    "Verwijderen mislukt: " + (it.message ?: "onbekende fout"),
                                                    Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }.start()
                                }
                                .show()
                        }
                    })
                }

                details.addView(android.widget.Button(this).apply {
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
            card.addView(details)
            groupDetails.addView(card)
            groupDetails.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(this@WakePcActivity, R.color.line)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(list) }
        AlertDialog.Builder(this).setTitle("Verbonden apparaten").setView(scroll).setNegativeButton("Sluiten", null).show()
    }

    private fun showOwnerPairingDialog() {
        val input = EditText(this).apply {
            hint = "OWNER-... code uit beveiligde Dev Hub"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(24, 14, 24, 14)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Main-meldingen koppelen")
            .setMessage(
                "Open in de beveiligde Dev Hub 'Licenties beheren' en maak " +
                "daar een eenmalige eigenaar-koppelcode. Voer hem hier binnen 10 minuten in."
            )
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Koppelen", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                submit.isEnabled = false
                Thread {
                    val paired = runCatching {
                        MainLicenseClient.pairOwner(this, input.text.toString())
                    }.getOrDefault(false)
                    runOnUiThread {
                        submit.isEnabled = true
                        if (paired) {
                            dialog.dismiss()
                            Toast.makeText(this, "Main-aanmeldmeldingen gekoppeld.",
                                Toast.LENGTH_LONG).show()
                            AccessRequestNotificationWorker.checkNow(this)
                        } else {
                            input.error = "Koppelen mislukt: controleer de code en vervaldatum."
                        }
                    }
                }.start()
            }
        }
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
