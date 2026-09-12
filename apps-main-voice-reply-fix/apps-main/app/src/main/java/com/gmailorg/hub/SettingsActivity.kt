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
    private lateinit var pinInput: EditText
    private lateinit var pinErrorText: TextView
    private lateinit var parkingEmptyState: TextView
    private lateinit var notificationSoundName: TextView
    private lateinit var adapter: SettingsParkingAdapter

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
        ParkingAddressStore.init(applicationContext)
        HiddenTilesStore.init(applicationContext)

        lockSection = findViewById(R.id.lockSection)
        unlockedSection = findViewById(R.id.unlockedSection)
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

        setupCarRadioSection()
        setupNotificationReplySection()
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
                CarRadioConnectionService.start(this)
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
            pinErrorText.visibility = View.GONE
            lockSection.visibility = View.GONE
            unlockedSection.visibility = View.VISIBLE
            refreshParkingList()
            refreshHiddenTiles()
        } else {
            pinErrorText.visibility = View.VISIBLE
            pinInput.text.clear()
        }
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
        "currency" to "EUR/SRD-koers",
        "whatsapp" to "WhatsApp",
        "googlehome" to "Google Home"
    )

    private fun refreshHiddenTiles() {
        val container = findViewById<LinearLayout>(R.id.hiddenTilesContainer)
        val emptyState = findViewById<TextView>(R.id.hiddenTilesEmptyState)
        container.removeAllViews()

        val hiddenIds = HiddenTilesStore.getAllHidden().toList()
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
