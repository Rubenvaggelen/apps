package com.gmailorg.hub

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PIN_CODE = "290114"
    }

    private lateinit var lockSection: LinearLayout
    private lateinit var unlockedSection: LinearLayout
    private lateinit var settingsContent: LinearLayout
    private lateinit var pinInput: EditText
    private lateinit var pinErrorText: TextView
    private lateinit var parkingEmptyState: TextView
    private lateinit var notificationSoundName: TextView
    private lateinit var adapter: SettingsParkingAdapter
    private var activeAdminPin = ""
    private var deviceManagerAdded = false
    private var ownerRecoveryAdded = false

    private val pickRingtone = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        NotificationSoundStore.setSoundUri(this, uri)
        refreshSoundName()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        MenuButtonHelper.attach(this)
        ParkingAddressStore.init(applicationContext)
        HiddenTilesStore.init(applicationContext)

        lockSection = findViewById(R.id.lockSection)
        unlockedSection = findViewById(R.id.unlockedSection)
        settingsContent = findViewById(R.id.settingsContent)
        pinInput = findViewById(R.id.pinInput)
        pinErrorText = findViewById(R.id.pinErrorText)
        parkingEmptyState = findViewById(R.id.parkingEmptyState)
        notificationSoundName = findViewById(R.id.notificationSoundName)
        refreshSoundName()
        findViewById<View>(R.id.chooseSoundButton).setOnClickListener { openRingtonePicker() }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.unlockButton).setOnClickListener { tryUnlock() }
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryUnlock()
                true
            } else {
                false
            }
        }

        val list = findViewById<RecyclerView>(R.id.parkingAddressList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = SettingsParkingAdapter(
            onDelete = { address ->
                ParkingAddressStore.remove(address.id)
                ParkingGeofenceManager.unregister(this, address.id)
                refreshParkingList()
            }
        )
        list.adapter = adapter

        setupKieSection()
        setupCarRadioSection()
        setupNotificationReplySection()

        if (MainDeviceRegistry.isOwnerEligible()) {
            setupDeviceManagerSection()
        }
    }

    private fun setupKieSection() {
        val input = findViewById<EditText>(R.id.groqKeyInput)
        val status = findViewById<TextView>(R.id.groqKeyStatus)
        val save = findViewById<View>(R.id.saveGroqKeyButton)
        val clear = findViewById<View>(R.id.clearGroqKeyButton)

        // De KIE-key is bewust in deze build ingebouwd. Toon de key zelf nooit
        // in de interface en log hem nergens.
        input.visibility = View.GONE
        save.visibility = View.GONE
        clear.visibility = View.GONE
        status.text = "ChatGPT GPT-5.2 actief ✓ via KIE — key is ingebouwd"
        status.setTextColor(ContextCompat.getColor(this, R.color.amber))
    }

    private fun setupNotificationReplySection() {
        val switch = findViewById<Switch>(R.id.notificationReplySwitch)
        switch.isChecked = NotificationReplySettings.isEnabled(this)
        switch.setOnCheckedChangeListener { _, checked ->
            NotificationReplySettings.setEnabled(this, checked)
        }
    }

    private fun setupCarRadioSection() {
        val deviceNameText = findViewById<TextView>(R.id.carRadioDeviceName)
        val chooseCarRadioButton = findViewById<View>(R.id.chooseCarRadioButton)
        val carRadioSwitch = findViewById<Switch>(R.id.carRadioSwitch)

        fun refreshDeviceName() {
            val name = CarRadioForwarder.selectedDeviceName(this)
            deviceNameText.text = name ?: "Geen autoradio gekozen"
        }
        refreshDeviceName()
        carRadioSwitch.isChecked = CarRadioForwarder.isEnabled(this)

        chooseCarRadioButton.setOnClickListener {
            ensureBluetoothPermissionThen { showPairedDevicePicker { refreshDeviceName() } }
        }

        carRadioSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && CarRadioForwarder.selectedDeviceAddress(this) == null) {
                Toast.makeText(this, "Kies eerst je autoradio.", Toast.LENGTH_SHORT).show()
                carRadioSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (checked) {
                val micGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!micGranted) {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                CarRadioForwarder.setEnabled(this, true)
            } else {
                CarRadioForwarder.setEnabled(this, false)
                CarRadioConnectionService.stop(this)
            }
        }
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* voor spraak-antwoorden vanuit de auto; werkt zonder ook, alleen zonder spraakinvoer */ }

    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* resultaat wordt bij de volgende actie opnieuw gecheckt */ }

    private fun ensureBluetoothPermissionThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        action()
    }

    private fun showPairedDevicePicker(onPicked: () -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth is niet beschikbaar op dit toestel.", Toast.LENGTH_LONG).show()
            return
        }
        val bondedDevices = try {
            adapter.bondedDevices.toList()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Geen toestemming voor Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }
        if (bondedDevices.isEmpty()) {
            Toast.makeText(this, "Nog geen gekoppelde Bluetooth-apparaten. Koppel je autoradio eerst via de Bluetooth-instellingen van je telefoon.", Toast.LENGTH_LONG).show()
            return
        }

        val names = bondedDevices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kies je autoradio")
            .setItems(names) { _, index ->
                val device = bondedDevices[index]
                CarRadioForwarder.setSelectedDevice(this, device.address, device.name ?: device.address)
                onPicked()
            }
            .show()
    }

    private fun tryUnlock() {
        if (pinInput.text.toString() == PIN_CODE) {
            activeAdminPin = pinInput.text.toString()
            pinErrorText.visibility = View.GONE
            lockSection.visibility = View.GONE
            unlockedSection.visibility = View.VISIBLE
            refreshParkingList()
            refreshHiddenTiles()

            Thread {
                var owner = runCatching {
                    MainDeviceRegistry.ownerStatus(this, activeAdminPin)
                }.getOrDefault(false)

                if (!owner && MainDeviceRegistry.isOwnerEligible()) {
                    owner = runCatching {
                        MainDeviceRegistry.claimOwner(this, activeAdminPin)
                    }.getOrDefault(false)
                }

                runOnUiThread {
                    if (owner) setupDeviceManagerSection()
                    else setupOwnerRecoverySection()
                }
            }.start()
        } else {
            pinErrorText.visibility = View.VISIBLE
            pinInput.text.clear()
        }
    }

    private fun setupOwnerRecoverySection() {
        if (ownerRecoveryAdded) return
        ownerRecoveryAdded = true

        val divider = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.line))
        }
        settingsContent.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply { setMargins(0, 24, 0, 20) }
        )

        settingsContent.addView(TextView(this).apply {
            text = "Eigenaar herstellen"
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_main))
            setPadding(0, 0, 0, 8)
        })

        settingsContent.addView(TextView(this).apply {
            text = "Alleen gebruiken wanneer je naar een nieuwe telefoon bent overgestapt. Hiervoor is de aparte herstelcode nodig."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_dim))
            setPadding(0, 0, 0, 10)
        })

        val recover = android.widget.Button(this).apply {
            text = "Dit toestel als eigenaar herstellen"
            setOnClickListener { promptOwnerRecovery(this) }
        }
        settingsContent.addView(
            recover,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun promptOwnerRecovery(trigger: View) {
        val input = EditText(this).apply {
            hint = "Herstelcode"
            setSingleLine(true)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Eigenaar herstellen")
            .setMessage("Hiermee wordt dit toestel de nieuwe eigenaar van The One Main. Het vorige eigenaarstoestel verliest daarna de beheerrechten.")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Overzetten", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val recoveryCode = input.text?.toString().orEmpty().trim()
                if (recoveryCode.length < 12) {
                    input.error = "Vul de volledige herstelcode in"
                    return@setOnClickListener
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                trigger.isEnabled = false

                Thread {
                    val result = runCatching {
                        MainDeviceRegistry.heartbeat(this)
                        MainDeviceRegistry.recoverOwner(this, activeAdminPin, recoveryCode)
                    }

                    runOnUiThread {
                        trigger.isEnabled = true
                        result.onSuccess { owner ->
                            if (owner) {
                                dialog.dismiss()
                                Toast.makeText(
                                    this,
                                    "Dit toestel is nu de eigenaar van The One Main.",
                                    Toast.LENGTH_LONG
                                ).show()
                                setupDeviceManagerSection()
                            } else {
                                input.error = "Herstel is niet gelukt"
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            }
                        }.onFailure {
                            input.error = "Herstelcode onjuist of herstel niet toegestaan"
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun setupDeviceManagerSection() {
        if (deviceManagerAdded) return
        deviceManagerAdded = true

        val divider = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.line))
        }
        settingsContent.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply { setMargins(0, 24, 0, 20) }
        )

        settingsContent.addView(TextView(this).apply {
            text = "Verbonden apparaten"
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_main))
            setPadding(0, 0, 0, 8)
        })

        settingsContent.addView(TextView(this).apply {
            text = "Bekijk welke apparaten The One Main gebruiken en blokkeer of deblokkeer ze."
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_dim))
            setPadding(0, 0, 0, 10)
        })

        val manage = android.widget.Button(this).apply {
            text = "Apparaten beheren"
            setOnClickListener { trigger ->
                openOwnerDeviceManager(trigger)
            }
        }
        settingsContent.addView(
            manage,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun openOwnerDeviceManager(trigger: View) {
        trigger.isEnabled = false
        Thread {
            val pin = if (activeAdminPin.isNotBlank()) activeAdminPin else PIN_CODE

            var owner = runCatching {
                MainDeviceRegistry.ownerStatus(this, pin)
            }.getOrDefault(false)

            if (!owner && MainDeviceRegistry.isOwnerEligible()) {
                runCatching { MainDeviceRegistry.heartbeat(this) }
                owner = runCatching {
                    MainDeviceRegistry.claimOwner(this, pin)
                }.getOrDefault(false)
            }

            runOnUiThread {
                trigger.isEnabled = true
                if (owner) {
                    activeAdminPin = pin
                    loadAndShowDevices(trigger)
                } else {
                    Toast.makeText(
                        this,
                        "Dit toestel heeft geen eigenaarstoegang tot apparatenbeheer.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun loadAndShowDevices(trigger: View) {
        if (activeAdminPin.isBlank()) return
        trigger.isEnabled = false
        Thread {
            val result = runCatching { MainDeviceRegistry.listDevices(this, activeAdminPin) }
            runOnUiThread {
                trigger.isEnabled = true
                result.onSuccess { showDevicesDialog(it) }
                    .onFailure {
                        Toast.makeText(
                            this,
                            "Apparaten konden niet worden geladen: ${it.message ?: "onbekende fout"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }.start()
    }

    private fun showDevicesDialog(devices: List<MainRegisteredDevice>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 8, 18, 8)
        }

        if (devices.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nog geen apparaten geregistreerd."
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_dim))
                textSize = 14f
                setPadding(12, 20, 12, 20)
            })
        }

        val currentDeviceId = MainDeviceRegistry.deviceId(this)

        devices.forEach { device ->
            val isCurrentDevice = device.id == currentDeviceId
            val personLabel = device.personName.ifBlank { "Naam nog niet ingevuld" }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
            }

            val title = TextView(this).apply {
                text = (if (device.online) "●  " else "○  ") + personLabel +
                    if (device.owner) "  •  The One" else ""
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    ContextCompat.getColor(
                        this@SettingsActivity,
                        if (device.online) R.color.amber else R.color.text_main
                    )
                )
            }
            card.addView(title)

            card.addView(TextView(this).apply {
                text = device.name +
                    if (isCurrentDevice) "  •  Dit apparaat" else ""
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_dim))
                setPadding(0, 3, 0, 2)
            })

            val state = TextView(this).apply {
                text = deviceStateText(device)
                textSize = 12f
                setTextColor(
                    ContextCompat.getColor(
                        this@SettingsActivity,
                        if (device.blocked) android.R.color.holo_red_light else R.color.text_dim
                    )
                )
                setPadding(0, 5, 0, 8)
            }
            card.addView(state)

            if (device.owner) {
                card.addView(TextView(this).apply {
                    text = "The One • beheerder • kan niet worden geblokkeerd"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.amber))
                    setPadding(0, 4, 0, 6)
                })
            } else {
                val action = android.widget.Button(this).apply {
                    text = if (device.blocked) "Deblokkeren" else "Blokkeren"
                setOnClickListener {
                    isEnabled = false
                    val newBlocked = !device.blocked
                    Thread {
                        val result = runCatching {
                            MainDeviceRegistry.setBlocked(this@SettingsActivity, activeAdminPin, device.id, newBlocked)
                        }
                        runOnUiThread {
                            result.onSuccess {
                                text = if (newBlocked) "Deblokkeren" else "Blokkeren"
                                state.text =
                                    (if (newBlocked) "GEBLOKKEERD • " else "") +
                                        deviceLastSeenText(device)
                                state.setTextColor(
                                    ContextCompat.getColor(
                                        this@SettingsActivity,
                                        if (newBlocked) android.R.color.holo_red_light else R.color.text_dim
                                    )
                                )
                                Toast.makeText(
                                    this@SettingsActivity,
                                    if (newBlocked) "$personLabel is geblokkeerd." else "$personLabel is gedeblokkeerd.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure {
                                Toast.makeText(
                                    this@SettingsActivity,
                                    "Wijzigen mislukt: ${it.message ?: "onbekende fout"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            isEnabled = true
                        }
                    }.start()
                }
                }
                card.addView(action)
            }

            list.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            list.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.line))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }

        AlertDialog.Builder(this)
            .setTitle("Verbonden apparaten")
            .setView(scroll)
            .setNegativeButton("Sluiten", null)
            .show()
    }

    private fun deviceStateText(device: MainRegisteredDevice): String {
        val prefix = if (device.blocked) "GEBLOKKEERD • " else ""
        return prefix + deviceLastSeenText(device)
    }

    private fun deviceLastSeenText(device: MainRegisteredDevice): String {
        val onlineText = if (device.online) "Online" else "Offline"
        val platform = listOf(device.platform, if (device.version.isBlank()) "" else "build ${device.version}")
            .filter { it.isNotBlank() }
            .joinToString(" • ")
        val seen = if (device.lastSeen > 0L) {
            val formatter = java.text.SimpleDateFormat("dd-MM HH:mm", java.util.Locale("nl", "NL"))
            "Laatst gezien ${formatter.format(java.util.Date(device.lastSeen * 1000L))}"
        } else {
            "Nog niet gezien"
        }
        return listOf(onlineText, platform, seen).filter { it.isNotBlank() }.joinToString(" • ")
    }

    private fun refreshParkingList() {
        val items = ParkingAddressStore.getAll()
        adapter.updateItems(items)
        parkingEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // Labels van de vaste tegels — moet in sync blijven met de lijst in HomeActivity.refreshTiles().
    private val fixedTileLabels = mapOf(
        "notifications" to "Meldingen",
        "mail" to "Mail & Kalender",
        "route" to "Route",
        "household" to "Huishouden",
        "movies" to "Films, Series & Muziek",
        "parking" to "Parkeren",
        "settings" to "Instellingen",
        "ask" to "Vraag het",
        "recipes" to "Recepten",
        "news" to "Nieuws",
        "radio" to "Radio",
        "currency" to "Koers (EUR / SRD / USD)",
        "lifestyle" to "Lifestyle",
        "remote_pc" to "Laptop",
        "whatsapp" to "WhatsApp",
        "googlehome" to "Google Home"
    )

    private fun refreshHiddenTiles() {
        val container = findViewById<LinearLayout>(R.id.hiddenTilesContainer)
        val emptyState = findViewById<TextView>(R.id.hiddenTilesEmptyState)
        container.removeAllViews()

        val hiddenIds = HiddenTilesStore.getAllHidden().filterNot { it == "finance" }
        emptyState.visibility = if (hiddenIds.isEmpty()) View.VISIBLE else View.GONE

        hiddenIds.forEach { id ->
            val label = fixedTileLabels[id] ?: id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 10)
            }
            val labelView = TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val restoreButton = android.widget.Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless).apply {
                text = "Terugzetten"
                setTextColor(ContextCompat.getColor(context, R.color.amber))
                setOnClickListener {
                    HiddenTilesStore.unhide(id)
                    refreshHiddenTiles()
                    Toast.makeText(this@SettingsActivity, "$label teruggezet", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(labelView)
            row.addView(restoreButton)
            container.addView(row)
        }
    }

    private fun openRingtonePicker() {
        val currentUri = NotificationSoundStore.getSoundUri(this)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Kies meldingsgeluid")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
        }
        pickRingtone.launch(intent)
    }

    private fun refreshSoundName() {
        notificationSoundName.text = NotificationSoundStore.getSoundDisplayName(this)
    }
}

class SettingsParkingAdapter(
    private val onDelete: (ParkingAddress) -> Unit
) : RecyclerView.Adapter<SettingsParkingAdapter.ViewHolder>() {

    private var items: List<ParkingAddress> = emptyList()

    fun updateItems(newItems: List<ParkingAddress>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.addressText)
        val deleteButton: ImageButton = view.findViewById(R.id.addressDeleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_parking_address, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text.text = item.address
        holder.deleteButton.setOnClickListener { onDelete(item) }
    }
}
