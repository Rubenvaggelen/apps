package com.gmailorg.carradio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Startscherm/launcher voor de K2401. De telefoon-app verandert hierdoor niet.
 * Auto-onvriendelijke tegels (Recepten, Films/Series, Vraag het en EUR/SRD) bestaan hier niet.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var tileGrid: GridLayout
    private lateinit var clockText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val statusListener: (String) -> Unit = { text -> statusText.text = text }

    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startConnectionService() }

    private val clockTick = object : Runnable {
        override fun run() {
            clockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        tileGrid = findViewById(R.id.tileGrid)
        clockText = findViewById(R.id.clockText)

        MessageBus.addStatusListener(statusListener)
        buildTiles()
        ensurePermissionThenStart()
        handler.post(clockTick)
        UpdateChecker.checkForUpdate(this)
    }

    private fun buildTiles() {
        tileGrid.removeAllViews()
        addTile("The One Car", R.drawable.the_one_logo, featured = true) {
            startActivity(Intent(this, WhatsAppConversationsActivity::class.java))
        }
        addTile("Meldingen", R.drawable.ic_home_notifications_fancy) {
            startActivity(Intent(this, MessageLogActivity::class.java))
        }
        addTile("Mail & Kalender", R.drawable.ic_home_mail_fancy) {
            openUrl("https://mail.google.com/")
        }
        addTile("Route", R.drawable.ic_home_route_fancy) {
            openGeo("geo:0,0?q=")
        }
        addTile("Huishouden", R.drawable.ic_home_household_fancy) {
            if (!launchPackage("com.google.android.apps.chromecast.app")) {
                Toast.makeText(this, "Google Home staat niet op deze radio", Toast.LENGTH_SHORT).show()
            }
        }
        addTile("Muziek", R.drawable.ic_home_music_fancy) { openMusic() }
        // USB staat bewust in dezelfde kolom direct onder Muziek op het 3-koloms K2401-dashboard.
        addTile("Parkeren", R.drawable.ic_home_parking_fancy) {
            openGeo("geo:0,0?q=parking")
        }
        addTile("Nieuws", R.drawable.ic_home_news_fancy) {
            openUrl("https://news.google.com/")
        }
        addTile("USB", R.drawable.ic_usb_music) {
            startActivity(Intent(this, UsbMusicActivity::class.java))
        }
        addTile("Radio", R.drawable.ic_home_radio_fancy) { openSystemRadio() }
        addTile("Instellingen", R.drawable.ic_home_settings_fancy) {
            startActivity(Intent(this, CarSettingsActivity::class.java))
        }
    }

    private fun addTile(label: String, iconRes: Int, featured: Boolean = false, action: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.view_car_tile, tileGrid, false)
        view.findViewById<TextView>(R.id.tileLabel).text = label
        view.findViewById<ImageView>(R.id.tileIcon).setImageResource(iconRes)
        if (featured) view.findViewById<View>(R.id.tileRoot).setBackgroundResource(R.drawable.bg_car_tile_featured)
        view.setOnClickListener {
            cancelStartupGuard()
            action()
        }

        val params = GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(7.dp, 7.dp, 7.dp, 7.dp)
        }
        tileGrid.addView(view, params)
    }

    private fun ensurePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        startConnectionService()
    }

    private fun startConnectionService() {
        val intent = Intent(this, BluetoothListenerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, intent)
            else startService(intent)
        } catch (_: Exception) {
            statusText.text = "Kon Bluetooth-service niet starten"
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Geen browser gevonden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGeo(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            openUrl("https://maps.google.com/")
        }
    }

    private fun openMusic() {
        val candidates = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.google.android.music"
        )
        for (pkg in candidates) if (launchPackage(pkg)) return
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_MUSIC_PLAYER))
        } catch (_: Exception) {
            Toast.makeText(this, "Geen muziek-app gevonden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemRadio() {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString() }
        val match = candidates.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase(Locale.ROOT)
            label == "radio" || label.contains("fm radio") || label.startsWith("radio ")
        }
        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "Radio-app gevonden maar kon niet openen", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Ik kon de ingebouwde radio-app nog niet automatisch vinden", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { startActivity(intent); true } catch (_: Exception) { false }
    }

    private fun cancelStartupGuard() {
        try {
            val intent = Intent(this, BluetoothListenerService::class.java).apply {
                action = BluetoothListenerService.ACTION_CANCEL_STARTUP
            }
            startService(intent)
        } catch (_: Exception) {}
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        cancelStartupGuard()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        statusText.text = MessageBus.currentStatus()
    }

    override fun onDestroy() {
        MessageBus.removeStatusListener(statusListener)
        handler.removeCallbacks(clockTick)
        super.onDestroy()
    }
}
