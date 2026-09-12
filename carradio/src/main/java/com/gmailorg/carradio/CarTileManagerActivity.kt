package com.gmailorg.carradio

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CarTileManagerActivity : AppCompatActivity() {
    private val fixedLabels = linkedMapOf(
        "theonecar" to "The One Car", "notifications" to "Meldingen", "mail" to "Mail & Kalender",
        "route" to "Route", "household" to "Huishouden", "music" to "Muziek", "parking" to "Parkeren",
        "news" to "Nieuws", "settings" to "Instellingen"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_tile_manager)
        findViewById<Button>(R.id.restoreAllTilesButton).setOnClickListener { CarTileStore.restoreAll(this); rebuild() }
        rebuild()
    }
    private fun rebuild() {
        val container = findViewById<LinearLayout>(R.id.hiddenTilesList)
        container.removeAllViews()
        val hidden = CarTileStore.hidden(this).filter { fixedLabels.containsKey(it) }
        if (hidden.isEmpty()) {
            container.addView(TextView(this).apply { text = "Geen verborgen vaste tegels."; setTextColor(ContextCompat.getColor(context, R.color.text_dim)); textSize = 16f })
        } else hidden.forEach { id ->
            val btn = Button(this).apply { text = "Terugzetten: ${fixedLabels[id] ?: id}"; setOnClickListener { CarTileStore.restore(this@CarTileManagerActivity, id); rebuild() } }
            container.addView(btn)
        }
    }
}
