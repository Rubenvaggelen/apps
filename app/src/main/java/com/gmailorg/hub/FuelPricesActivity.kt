package com.gmailorg.hub

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class FuelPricesActivity : AppCompatActivity() {
    companion object {
        private const val LOCATION_REQUEST = 7421
        private const val MAX_RESULTS = 10
    }

    private lateinit var status: TextView
    private lateinit var container: LinearLayout
    private lateinit var refreshButton: Button
    private lateinit var e10Button: Button
    private lateinit var e5Button: Button
    private lateinit var dieselButton: Button
    private var selectedFuel = FuelPriceClient.Fuel.E10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fuel_prices)
        MenuButtonHelper.attach(this)

        status = findViewById(R.id.fuelStatus)
        container = findViewById(R.id.fuelStationContainer)
        refreshButton = findViewById(R.id.fuelRefreshButton)
        e10Button = findViewById(R.id.fuelE10Button)
        e5Button = findViewById(R.id.fuelE5Button)
        dieselButton = findViewById(R.id.fuelDieselButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        refreshButton.setOnClickListener { loadPrices() }
        e10Button.setOnClickListener { selectFuel(FuelPriceClient.Fuel.E10) }
        e5Button.setOnClickListener { selectFuel(FuelPriceClient.Fuel.E5) }
        dieselButton.setOnClickListener { selectFuel(FuelPriceClient.Fuel.DIESEL) }
        updateFuelButtons()
        loadPrices()
    }

    private fun selectFuel(fuel: FuelPriceClient.Fuel) {
        if (selectedFuel == fuel) return
        selectedFuel = fuel
        updateFuelButtons()
        loadPrices()
    }

    private fun updateFuelButtons() {
        val active = ContextCompat.getColor(this, R.color.amber)
        val inactive = ContextCompat.getColor(this, R.color.surface_raised)
        val activeText = ContextCompat.getColor(this, R.color.on_amber)
        val inactiveText = ContextCompat.getColor(this, R.color.text_main)
        listOf(
            e10Button to FuelPriceClient.Fuel.E10,
            e5Button to FuelPriceClient.Fuel.E5,
            dieselButton to FuelPriceClient.Fuel.DIESEL
        ).forEach { (button, fuel) ->
            val selected = fuel == selectedFuel
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected) active else inactive)
            button.setTextColor(if (selected) activeText else inactiveText)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun loadPrices() {
        if (!hasLocationPermission()) {
            status.text = "Locatietoegang nodig om tankstations binnen 5 km te zoeken."
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_REQUEST
            )
            return
        }
        loadPricesWithPermission()
    }

    @SuppressLint("MissingPermission")
    private fun loadPricesWithPermission() {
        refreshButton.isEnabled = false
        container.removeAllViews()
        status.text = "Huidige locatie ophalen…"

        val fused = LocationServices.getFusedLocationProviderClient(this)
        val token = CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { current ->
                if (current != null) {
                    fetchPrices(current.latitude, current.longitude)
                } else {
                    fused.lastLocation
                        .addOnSuccessListener { last ->
                            if (last != null) fetchPrices(last.latitude, last.longitude) else showLocationError()
                        }
                        .addOnFailureListener { showLocationError() }
                }
            }
            .addOnFailureListener {
                fused.lastLocation
                    .addOnSuccessListener { last ->
                        if (last != null) fetchPrices(last.latitude, last.longitude) else showLocationError()
                    }
                    .addOnFailureListener { showLocationError() }
            }
    }

    private fun fetchPrices(lat: Double, lon: Double) {
        status.text = "${selectedFuel.label} prijzen binnen 5 km ophalen…"
        Thread {
            runCatching { FuelPriceClient.fetchNearby(lat, lon, selectedFuel) }
                .onSuccess { stations -> runOnUiThread { renderStations(stations) } }
                .onFailure { error ->
                    runOnUiThread {
                        refreshButton.isEnabled = true
                        container.removeAllViews()
                        status.text = "Kon brandstofprijzen niet ophalen: ${error.message ?: "onbekende fout"}"
                    }
                }
        }.start()
    }

    private fun renderStations(stations: List<FuelPriceClient.Station>) {
        refreshButton.isEnabled = true
        container.removeAllViews()
        val displayed = stations.take(MAX_RESULTS)
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        if (displayed.isEmpty()) {
            status.text = "Geen ${selectedFuel.label} prijs gevonden binnen 5 km. Probeer een ander brandstoftype of ververs later opnieuw."
            return
        }
        status.text = "${displayed.size}${if (stations.size > MAX_RESULTS) "+" else ""} stations • goedkoopste eerst • lijst opgehaald om $time"

        displayed.forEachIndexed { index, station -> container.addView(createStationCard(index, station)) }
    }

    private fun createStationCard(index: Int, station: FuelPriceClient.Station): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(ContextCompat.getColor(context, if (index == 0) R.color.surface_raised else R.color.surface))
            isClickable = true
            isFocusable = true
            setOnClickListener { openInWaze(station) }
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = if (index == 0) "★ ${station.name}" else "${index + 1}. ${station.name}"
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            setTypeface(typeface, Typeface.BOLD)
        }
        top.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val price = TextView(this).apply {
            text = String.format(Locale("nl", "NL"), "€ %.3f", station.price)
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.amber))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        top.addView(price)
        card.addView(top)

        val addressText = listOf(station.address, station.city).filter { it.isNotBlank() }.joinToString(", ")
        TextView(this).apply {
            text = buildString {
                if (addressText.isNotBlank()) append(addressText).append("\n")
                append(String.format(Locale("nl", "NL"), "%.1f km afstand • tik voor Waze", station.distanceKm))
            }
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            setPadding(0, dp(5), 0, 0)
        }.also(card::addView)

        return card
    }

    private fun openInWaze(station: FuelPriceClient.Station) {
        val uri = Uri.parse("https://waze.com/ul?ll=${station.lat},${station.lon}&navigate=yes")
        val wazeIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.waze") }
        try {
            startActivity(wazeIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun showLocationError() {
        refreshButton.isEnabled = true
        status.text = "Locatie niet beschikbaar. Zet GPS aan en probeer opnieuw."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) loadPricesWithPermission()
            else Toast.makeText(this, "Zonder locatietoegang kan The One geen tankstations binnen 5 km zoeken.", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
