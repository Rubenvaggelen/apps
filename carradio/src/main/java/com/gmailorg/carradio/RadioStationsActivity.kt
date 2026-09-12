package com.gmailorg.carradio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Car-friendly mirror of the stations that are available in The One on the phone.
 * This deliberately does not launch the K2401 factory FM radio.
 */
class RadioStationsActivity : AppCompatActivity() {

    private data class Station(val name: String, val url: String)

    private val stations = listOf(
        Station("Radio 538", "https://www.538.nl/radio/luisteren"),
        Station("FunX", "https://www.funx.nl/"),
        Station("FunX Slow Jamz", "https://www.funx.nl/slowjamz/online-radio-luisteren"),
        Station("Sky Radio", "https://www.skyradio.nl/"),
        Station("LIM FM SU", "https://www.limfmsu.com/"),
        Station("Soul Radio", "https://soulradio.nl/"),
        Station("Radio Veronica", "https://www.radioveronica.nl/"),
        Station("Qmusic", "https://www.qmusic.nl/")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radio_stations)

        findViewById<Button>(R.id.radioBackButton).setOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.stationContainer)
        stations.forEach { station -> container.addView(createStationButton(station)) }
    }

    private fun createStationButton(station: Station): Button {
        return Button(this).apply {
            text = station.name
            textSize = 18f
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(this@RadioStationsActivity, R.color.text_main))
            backgroundTintList = ContextCompat.getColorStateList(this@RadioStationsActivity, R.color.surface_raised)
            setPadding(22.dp, 0, 22.dp, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                68.dp
            ).apply {
                bottomMargin = 10.dp
            }
            setOnClickListener { openStation(station) }
        }
    }

    private fun openStation(station: Station) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(station.url))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Kon ${station.name} niet openen", Toast.LENGTH_SHORT).show()
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
