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
        MenuButtonHelper.attach(this)
        findViewById<Button>(R.id.restoreAllTilesButton).setOnClickListener {
            CarTileStore.restoreAll(this)
            rebuild()
        }
        rebuild()
    }

    private fun rebuild() {
        val container = findViewById<LinearLayout>(R.id.hiddenTilesList)
        container.removeAllViews()
        val hidden = CarTileStore.hidden(this)

        container.addView(sectionTitle("Vaste tegels"))
        fixedLabels.forEach { (id, label) ->
            val isHidden = hidden.contains(id)
            val btn = Button(this).apply {
                text = if (isHidden) "Terugzetten: $label" else "Verbergen: $label"
                setOnClickListener {
                    if (isHidden) CarTileStore.restore(this@CarTileManagerActivity, id)
                    else CarTileStore.hide(this@CarTileManagerActivity, id)
                    rebuild()
                }
            }
            container.addView(btn)
        }

        container.addView(sectionTitle("Zelf toegevoegde apps"))
        val pm = packageManager
        val apps = CarTileStore.apps(this).toList().sortedBy { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase() }
            catch (_: Exception) { pkg }
        }
        if (apps.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Geen extra apps toegevoegd."
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                textSize = 16f
                setPadding(0, 8, 0, 8)
            })
        } else {
            apps.forEach { pkg ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                catch (_: Exception) { pkg }
                container.addView(Button(this).apply {
                    text = "Verwijderen: $label"
                    setOnClickListener {
                        CarTileStore.removeApp(this@CarTileManagerActivity, pkg)
                        rebuild()
                    }
                })
            }
        }
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(ContextCompat.getColor(context, R.color.amber))
        textSize = 18f
        setPadding(0, 18, 0, 8)
    }
}
