package com.gmailorg.carradio

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder

class RouteCarActivity : AppCompatActivity() {
    private lateinit var fromInput: EditText
    private lateinit var toInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_car)
        fromInput = findViewById(R.id.routeFrom)
        toInput = findViewById(R.id.routeTo)
        fromInput.setText("Huidige locatie")
        findViewById<Button>(R.id.routeCurrentLocationButton).setOnClickListener { fromInput.setText("Huidige locatie") }
        findViewById<Button>(R.id.routeCalculateButton).setOnClickListener { chooseNavigationApp() }
    }

    private fun chooseNavigationApp() {
        val from = fromInput.text.toString().trim()
        val to = toInput.text.toString().trim()
        if (to.isBlank()) {
            Toast.makeText(this, "Vul in waar je naartoe wilt", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("Google Maps", "Waze", "Browser / andere navigatie")
        AlertDialog.Builder(this)
            .setTitle("Route: ${if (from.isBlank()) "Huidige locatie" else from} → $to\nWelke navigatie wil je gebruiken?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openGoogleMaps(from, to)
                    1 -> openWaze(from, to)
                    else -> openBrowserRoute(from, to)
                }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun isCurrent(v: String) = v.isBlank() || v.equals("Huidige locatie", true) || v.equals("Mijn locatie", true)

    private fun openGoogleMaps(from: String, to: String) {
        val origin = if (isCurrent(from)) "" else "&origin=${enc(from)}"
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${enc(to)}$origin&travelmode=driving")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
        try { startActivity(intent) } catch (_: Exception) { openBrowserRoute(from, to) }
    }

    private fun openWaze(from: String, to: String) {
        if (!isCurrent(from)) {
            Toast.makeText(this, "Waze vertrekt vanaf je huidige locatie. Voor een ander vertrekpunt kies Google Maps.", Toast.LENGTH_LONG).show()
        }
        val uri = Uri.parse("https://waze.com/ul?q=${enc(to)}&navigate=yes")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.waze") }
        try { startActivity(intent) } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { openBrowserRoute(from, to) }
        }
    }

    private fun openBrowserRoute(from: String, to: String) {
        val origin = if (isCurrent(from)) "" else "&origin=${enc(from)}"
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${enc(to)}$origin&travelmode=driving")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {
            Toast.makeText(this, "Geen navigatie-app of browser gevonden", Toast.LENGTH_LONG).show()
        }
    }
}
