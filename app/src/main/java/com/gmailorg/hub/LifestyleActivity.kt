package com.gmailorg.hub

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class LifestyleActivity : AppCompatActivity() {

    companion object {
        private const val LOCATION_REQUEST = 7301
        private const val MIN_OUTDOOR_TEMP_C = 17.0
    }

    private lateinit var weatherStatus: TextView
    private lateinit var weatherAdvice: TextView
    private lateinit var weatherRefresh: Button
    private var weatherSectionVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lifestyle)
        MenuButtonHelper.attach(this)

        weatherStatus = findViewById(R.id.lifestyleWeatherStatus)
        weatherAdvice = findViewById(R.id.lifestyleWeatherAdvice)
        weatherRefresh = findViewById(R.id.lifestyleWeatherRefresh)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.lifestyleFitnessButton).setOnClickListener {
            startActivity(Intent(this, FitnessActivity::class.java))
        }
        findViewById<Button>(R.id.lifestyleWalkButton).setOnClickListener {
            showWeatherSectionAndLoad()
        }
        weatherRefresh.setOnClickListener { loadOutdoorForecast() }
    }

    private fun showWeatherSectionAndLoad() {
        weatherSectionVisible = true
        findViewById<View>(R.id.lifestyleWeatherContainer).visibility = View.VISIBLE
        loadOutdoorForecast()
    }

    private fun loadOutdoorForecast() {
        if (!hasLocationPermission()) {
            weatherStatus.text = "Locatietoegang nodig"
            weatherAdvice.text = "Geef The One locatietoegang zodat het weer voor jouw huidige omgeving kan worden bekeken."
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_REQUEST
            )
            return
        }
        loadOutdoorForecastWithPermission()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun loadOutdoorForecastWithPermission() {
        weatherRefresh.isEnabled = false
        weatherStatus.text = "Weer voor de komende dagen ophalen…"
        weatherAdvice.text = "The One zoekt dagen vanaf ${MIN_OUTDOOR_TEMP_C.toInt()} °C en weegt ook regen en wind mee."

        val fused = LocationServices.getFusedLocationProviderClient(this)
        fused.lastLocation
            .addOnSuccessListener { lastLocation ->
                if (lastLocation != null) {
                    fetchWeather(lastLocation.latitude, lastLocation.longitude)
                } else {
                    val tokenSource = CancellationTokenSource()
                    fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
                        .addOnSuccessListener { current ->
                            if (current != null) {
                                fetchWeather(current.latitude, current.longitude)
                            } else {
                                showLocationError()
                            }
                        }
                        .addOnFailureListener { showLocationError() }
                }
            }
            .addOnFailureListener { showLocationError() }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        WeatherForecastClient.fetch(lat, lon) { result ->
            weatherRefresh.isEnabled = true
            result.onSuccess { days -> renderForecast(days) }
                .onFailure { error ->
                    weatherStatus.text = "Weer kon niet worden opgehaald"
                    weatherAdvice.text = error.message ?: "Controleer je internetverbinding en probeer opnieuw."
                }
        }
    }

    private fun renderForecast(days: List<WeatherForecastClient.ForecastDay>) {
        if (days.isEmpty()) {
            weatherStatus.text = "Geen weersverwachting beschikbaar"
            weatherAdvice.text = "Probeer het later opnieuw."
            return
        }

        val suitable = days
            .filter { !it.maxTempC.isNaN() && it.maxTempC >= MIN_OUTDOOR_TEMP_C }
            .sortedByDescending { outdoorScore(it) }

        weatherStatus.text = if (suitable.isEmpty()) {
            "Geen dag van minimaal ${MIN_OUTDOOR_TEMP_C.toInt()} °C gevonden"
        } else {
            "${suitable.size} geschikte dag${if (suitable.size == 1) "" else "en"} gevonden • minimum ${MIN_OUTDOOR_TEMP_C.toInt()} °C"
        }

        if (suitable.isEmpty()) {
            val warmest = days.maxByOrNull { it.maxTempC }
            weatherAdvice.text = buildString {
                append("De komende 7 dagen komt de maximumtemperatuur volgens de verwachting niet boven jouw grens van ${MIN_OUTDOOR_TEMP_C.toInt()} °C.\n\n")
                if (warmest != null) {
                    append("Warmste dag: ${formatDay(warmest)} — ${formatTemp(warmest.maxTempC)} °C, ${WeatherForecastClient.description(warmest.weatherCode)}, regen ${warmest.precipitationProbability}%, wind ${formatTemp(warmest.maxWindKmh)} km/u.\n\n")
                }
                append("Gebruik op koudere dagen eventueel je loopband of kies een ander binnenalternatief.\n\nWeerdata: Open-Meteo.")
            }
            return
        }

        weatherAdvice.text = buildString {
            append("BESTE DAGEN VOOR WANDELEN / STEPPEN\n")
            suitable.take(3).forEachIndexed { index, day ->
                append("\n${index + 1}. ${formatDay(day)} — ${formatTemp(day.maxTempC)} °C\n")
                append("${WeatherForecastClient.description(day.weatherCode).replaceFirstChar { it.uppercase() }} • regen ${day.precipitationProbability}% • wind ${formatTemp(day.maxWindKmh)} km/u\n")
                append(outdoorRecommendation(day))
                append("\n")
            }

            val otherSuitable = suitable.drop(3)
            if (otherSuitable.isNotEmpty()) {
                append("\nOOK WARM GENOEG\n")
                otherSuitable.forEach { day ->
                    append("• ${formatDay(day)}: ${formatTemp(day.maxTempC)} °C, regen ${day.precipitationProbability}%, wind ${formatTemp(day.maxWindKmh)} km/u\n")
                }
            }

            append("\nThe One selecteert alleen dagen vanaf ${MIN_OUTDOOR_TEMP_C.toInt()} °C. Voor steppen tellen regen en stevige wind extra zwaar mee.\n\nWeerdata: Open-Meteo.")
        }
    }

    private fun outdoorScore(day: WeatherForecastClient.ForecastDay): Double {
        // Comfort rond 21 °C scoort het hoogst. Regen en wind verlagen de score,
        // vooral omdat die bij steppen vervelender/veiligerheidsrelevanter zijn.
        val temperatureScore = 100.0 - abs(day.maxTempC - 21.0) * 4.0
        val rainPenalty = day.precipitationProbability * 0.65
        val windPenalty = when {
            day.maxWindKmh <= 15 -> 0.0
            else -> (day.maxWindKmh - 15.0) * 1.7
        }
        return temperatureScore - rainPenalty - windPenalty
    }

    private fun outdoorRecommendation(day: WeatherForecastClient.ForecastDay): String = when {
        day.precipitationProbability <= 25 && day.maxWindKmh <= 25 -> "✓ Zeer geschikt voor wandelen én steppen."
        day.precipitationProbability <= 45 && day.maxWindKmh <= 32 -> "✓ Goed om te wandelen; steppen kan als het droog blijft."
        day.precipitationProbability > 60 -> "△ Temperatuur is goed, maar de regenkans is hoog — wandelen is verstandiger dan steppen."
        day.maxWindKmh > 38 -> "△ Warm genoeg, maar behoorlijk winderig — liever wandelen dan steppen."
        else -> "✓ Geschikt om naar buiten te gaan; controleer vlak voor vertrek regen en wind."
    }

    private fun formatDay(day: WeatherForecastClient.ForecastDay): String =
        day.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("nl", "NL")))
            .replaceFirstChar { it.uppercase() }

    private fun formatTemp(value: Double): String =
        String.format(Locale("nl", "NL"), "%.0f", value)

    private fun showLocationError() {
        weatherRefresh.isEnabled = true
        weatherStatus.text = "Locatie niet beschikbaar"
        weatherAdvice.text = "Zet locatie aan en probeer opnieuw. The One gebruikt alleen je huidige locatie om de lokale weersverwachting op te halen."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                if (weatherSectionVisible) loadOutdoorForecastWithPermission()
            } else {
                Toast.makeText(this, "Zonder locatietoegang kan The One geen lokaal wandel-/stepweer adviseren.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
