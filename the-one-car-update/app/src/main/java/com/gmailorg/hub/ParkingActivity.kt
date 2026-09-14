package com.gmailorg.hub

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Scherm om nieuwe parkeeradressen toe te voegen, en om een parkeer-eindtijd
 * in te stellen met een eenmalige melding zodra die tijd is bereikt. Bewust
 * géén lijst met adressen hier — die zie je (en verwijder je) via
 * Instellingen (met pincode); de eindtijd-functie zelf is gewoon openbaar
 * toegankelijk, zonder pincode.
 */
class ParkingActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var confirmationText: TextView
    private lateinit var endTimeText: TextView
    private lateinit var clearEndTimeButton: View

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
        endTimeText = findViewById(R.id.parkingEndTimeText)
        clearEndTimeButton = findViewById(R.id.clearEndTimeButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.addAddressButton).setOnClickListener { addCurrentInput() }
        findViewById<View>(R.id.pickEndTimeButton).setOnClickListener { openTimePicker() }
        clearEndTimeButton.setOnClickListener { clearEndTime() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCurrentInput()
                true
            } else {
                false
            }
        }

        refreshEndTimeDisplay()

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

    // ---------------- Parkeer-eindtijd ----------------

    private fun openTimePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val now = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val target = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    // Als de gekozen tijd al voorbij is vandaag, bedoel je morgen.
                    if (before(Calendar.getInstance())) {
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
                scheduleEndTime(target.timeInMillis)
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun scheduleEndTime(triggerAtMillis: Long) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val pendingIntent = timerPendingIntent()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Geen toestemming voor exacte alarmen — val terug op een
                // (net iets minder precieze) inexact alarm, dat heeft geen
                // aparte toestemming nodig.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            ParkingTimerStore.set(this, triggerAtMillis)
            refreshEndTimeDisplay()
            Toast.makeText(this, "Melding ingesteld", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Kon geen melding instellen (geen toestemming voor alarmen).", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearEndTime() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(timerPendingIntent())
        ParkingTimerStore.clear(this)
        refreshEndTimeDisplay()
    }

    private fun refreshEndTimeDisplay() {
        val endTime = ParkingTimerStore.get(this)
        if (endTime == null) {
            endTimeText.text = "Geen eindtijd ingesteld"
            clearEndTimeButton.visibility = View.GONE
        } else {
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            endTimeText.text = "Melding om ${format.format(java.util.Date(endTime))}"
            clearEndTimeButton.visibility = View.VISIBLE
        }
    }

    private fun timerPendingIntent(): PendingIntent {
        val intent = Intent(this, ParkingTimerReceiver::class.java)
        return PendingIntent.getBroadcast(
            this, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
