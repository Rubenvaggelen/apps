package com.gmailorg.hub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Scherm om nieuwe parkeeradressen toe te voegen. Bewust géén lijst hier —
 * eenmaal toegevoegde adressen zie je (en verwijder je) via Instellingen
 * (met pincode), niet op dit scherm.
 */
class ParkingActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var confirmationText: TextView

    // Adres dat we willen registreren zodra de locatietoestemming binnen is.
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

        input = findViewById(R.id.newAddressInput)
        confirmationText = findViewById(R.id.confirmationText)

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
    }

    private fun addCurrentInput() {
        val text = input.text.toString()
        if (text.isBlank()) return
        val added = ParkingAddressStore.add(text) ?: return

        // Het veld leegmaken en verbergen (invoer + adres niet zichtbaar
        // laten staan) — alleen een korte bevestiging tonen.
        input.text.clear()
        confirmationText.text = "Toegevoegd: ${added.address}"
        confirmationText.visibility = View.VISIBLE

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
    }
}
