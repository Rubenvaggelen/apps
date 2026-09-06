package com.gmailorg.hub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ParkingActivity : AppCompatActivity() {

    private lateinit var adapter: ParkingAdapter
    private lateinit var emptyState: TextView
    private lateinit var input: EditText

    // Adres dat we willen toevoegen zodra de locatietoestemming binnen is.
    private var pendingAddressToRegister: ParkingAddress? = null

    private val requestForegroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestBackgroundLocationIfNeeded()
        } else {
            Toast.makeText(this, "Locatietoegang is nodig voor parkeermeldingen.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Ook zonder achtergrondlocatie werkt het gewoon terwijl de app open/actief is —
        // we gaan altijd door met registreren.
        registerPendingAddress()
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* resultaat negeren, meldingen werken al voor Android 13+ als dit geweigerd wordt niet, dat is prima */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parking)
        ParkingAddressStore.init(applicationContext)

        emptyState = findViewById(R.id.emptyState)
        input = findViewById(R.id.newAddressInput)

        val list = findViewById<RecyclerView>(R.id.addressList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ParkingAdapter(
            onDelete = { address ->
                ParkingAddressStore.remove(address.id)
                ParkingGeofenceManager.unregister(this, address.id)
                refresh()
            }
        )
        list.adapter = adapter

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.addAddressButton).setOnClickListener { addCurrentInput() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCurrentInput()
                true
            } else {
                false
            }
        }

        // Zorgt dat al bestaande adressen die nog geen coördinaten hebben
        // (bv. toegevoegd toen er nog geen toestemming was) alsnog geregistreerd worden.
        if (ParkingGeofenceManager.hasLocationPermission(this)) {
            ParkingGeofenceManager.syncAll(this)
        }

        refresh()
    }

    private fun addCurrentInput() {
        val text = input.text.toString()
        if (text.isBlank()) return
        val added = ParkingAddressStore.add(text) ?: return
        input.text.clear()
        refresh()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pendingAddressToRegister = added
        ensureForegroundLocationThenArm()
    }

    private fun ensureForegroundLocationThenArm() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestBackgroundLocationIfNeeded()
        } else {
            requestForegroundLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }
        registerPendingAddress()
    }

    private fun registerPendingAddress() {
        val address = pendingAddressToRegister ?: return
        pendingAddressToRegister = null
        ParkingGeofenceManager.registerNewAddress(this, address.id, address.address)
        Toast.makeText(this, "Melding ingesteld voor ${address.address}", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val items = ParkingAddressStore.getAll()
        adapter.updateItems(items)
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}

class ParkingAdapter(
    private val onDelete: (ParkingAddress) -> Unit
) : RecyclerView.Adapter<ParkingAdapter.ViewHolder>() {

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
