package com.gmailorg.carradio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** K2401 startscherm. De telefoon-home blijft volledig ongewijzigd. */
class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var tileGrid: GridLayout
    private lateinit var clockText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val statusListener: (String) -> Unit = { text -> statusText.text = text }

    private data class FixedTile(val id: String, val label: String, val icon: Int, val featured: Boolean = false, val action: (MainActivity) -> Unit)

    private val fixedTiles by lazy {
        listOf(
            FixedTile("theonecar", "The One Car", R.drawable.the_one_logo, true) { it.startActivity(Intent(it, WhatsAppConversationsActivity::class.java)) },
            FixedTile("notifications", "Meldingen", R.drawable.ic_home_notifications_fancy) { it.startActivity(Intent(it, MessageLogActivity::class.java)) },
            FixedTile("mail", "Mail & Kalender", R.drawable.ic_home_mail_fancy) { it.openMailCalendar() },
            FixedTile("route", "Route", R.drawable.ic_home_route_fancy) { it.startActivity(Intent(it, RouteCarActivity::class.java)) },
            FixedTile("household", "Huishouden", R.drawable.ic_home_household_fancy) { it.startActivity(Intent(it, HouseholdCarActivity::class.java)) },
            FixedTile("music", "Muziek", R.drawable.ic_home_music_fancy) { it.openMusic() },
            FixedTile("parking", "Parkeren", R.drawable.ic_home_parking_fancy) { it.startActivity(Intent(it, ParkingCarActivity::class.java)) },
            FixedTile("news", "Nieuws", R.drawable.ic_home_news_fancy) { it.startActivity(Intent(it, NewsCarActivity::class.java)) },
            // Positie 9 in het 3-koloms raster: direct onder Muziek.
            FixedTile("usb", "USB", R.drawable.ic_usb_music) { it.startActivity(Intent(it, UsbMusicActivity::class.java)) },
            FixedTile("radio", "Radio", R.drawable.ic_home_radio_fancy) { it.startActivity(Intent(it, RadioStationsActivity::class.java)) },
            FixedTile("settings", "Instellingen", R.drawable.ic_home_settings_fancy) { it.startActivity(Intent(it, CarSettingsActivity::class.java)) }
        )
    }

    private val requestBluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startConnectionService() }
    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val clockTick = object : Runnable {
        override fun run() { clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); handler.postDelayed(this, 30_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText); tileGrid = findViewById(R.id.tileGrid); clockText = findViewById(R.id.clockText)
        MessageBus.addStatusListener(statusListener)
        buildTiles(); ensurePermissionThenStart(); ensureNotificationPermission(); handler.post(clockTick); UpdateChecker.checkForUpdate(this)
    }

    private fun buildTiles() {
        tileGrid.removeAllViews()
        val hidden = CarTileStore.hidden(this)
        fixedTiles.filterNot { hidden.contains(it.id) }.forEach { tile ->
            addTile(tile.label, ContextCompat.getDrawable(this, tile.icon), tile.featured,
                onClick = { cancelStartupGuard(); tile.action(this) },
                onLongClick = { confirmHideFixed(tile.id, tile.label); true })
        }

        val pm = packageManager
        CarTileStore.apps(this).toList().sortedBy { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase(Locale.ROOT) } catch (_: Exception) { pkg }
        }.forEach { pkg ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { ContextCompat.getDrawable(this, R.drawable.ic_home_add_fancy) }
            addTile(label, icon, false,
                onClick = { cancelStartupGuard(); if (!launchPackage(pkg)) Toast.makeText(this, "App is niet meer geïnstalleerd", Toast.LENGTH_SHORT).show() },
                onLongClick = { confirmRemoveApp(pkg, label); true })
        }

        addTile("App toevoegen", ContextCompat.getDrawable(this, R.drawable.ic_home_add_fancy), false,
            onClick = { cancelStartupGuard(); startActivity(Intent(this, CarAppPickerActivity::class.java)) },
            onLongClick = { false })
    }

    private fun addTile(label: String, icon: Drawable?, featured: Boolean, onClick: () -> Unit, onLongClick: () -> Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.view_car_tile, tileGrid, false)
        view.findViewById<TextView>(R.id.tileLabel).text = label
        view.findViewById<ImageView>(R.id.tileIcon).setImageDrawable(icon)
        if (featured) view.findViewById<View>(R.id.tileRoot).setBackgroundResource(R.drawable.bg_car_tile_featured)
        view.setOnClickListener { onClick() }
        view.setOnLongClickListener { onLongClick() }
        val params = GridLayout.LayoutParams().apply {
            width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(7.dp, 7.dp, 7.dp, 7.dp)
        }
        tileGrid.addView(view, params)
    }

    private fun confirmHideFixed(id: String, label: String) {
        AlertDialog.Builder(this).setTitle("Tegel verwijderen?")
            .setMessage("$label wordt van The One Car verwijderd. Je kunt hem via Instellingen > Tegels beheren terugzetten.")
            .setPositiveButton("Verwijderen") { _, _ -> CarTileStore.hide(this, id); buildTiles() }
            .setNegativeButton("Annuleren", null).show()
    }
    private fun confirmRemoveApp(pkg: String, label: String) {
        AlertDialog.Builder(this).setTitle("Tegel verwijderen?").setMessage("$label wordt van het dashboard verwijderd.")
            .setPositiveButton("Verwijderen") { _, _ -> CarTileStore.removeApp(this, pkg); buildTiles() }
            .setNegativeButton("Annuleren", null).show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensurePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        startConnectionService()
    }
    private fun startConnectionService() {
        val intent = Intent(this, BluetoothListenerService::class.java)
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, intent) else startService(intent) }
        catch (_: Exception) { statusText.text = "Kon verbindingsservice niet starten" }
    }
    private fun openUrl(url: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { Toast.makeText(this, "Geen browser gevonden", Toast.LENGTH_SHORT).show() } }
    private fun openMailCalendar() {
        val url = Uri.parse("https://rubenvaggelen.github.io/Gmailorg/")
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, url)
        } catch (_: Exception) {
            openUrl(url.toString())
        }
    }
    private fun openMusic() {
        for (pkg in listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.google.android.music")) if (launchPackage(pkg)) return
        try { startActivity(Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER)) } catch (_: Exception) { Toast.makeText(this, "Geen muziek-app gevonden", Toast.LENGTH_SHORT).show() }
    }
    private fun launchPackage(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { startActivity(intent); true } catch (_: Exception) { false }
    }
    private fun cancelStartupGuard() { try { startService(Intent(this, BluetoothListenerService::class.java).apply { action = BluetoothListenerService.ACTION_CANCEL_STARTUP }) } catch (_: Exception) {} }
    override fun onUserInteraction() { super.onUserInteraction(); cancelStartupGuard() }
    override fun onResume() { super.onResume(); statusText.text = MessageBus.currentStatus(); buildTiles() }
    override fun onDestroy() { MessageBus.removeStatusListener(statusListener); handler.removeCallbacks(clockTick); super.onDestroy() }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
