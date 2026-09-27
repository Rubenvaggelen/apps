package com.gmailorg.carradio

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.os.Looper
import android.os.Environment
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import java.util.concurrent.Executors

/** K2401 startscherm. De telefoon-home blijft volledig ongewijzigd. */
class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var tileGrid: GridLayout
    private lateinit var clockText: TextView
    private lateinit var carRoot: FrameLayout
    private lateinit var youtubeOverlay: FrameLayout
    private lateinit var youtubePlayerSurface: PassThroughFrameLayout
    private lateinit var youtubeWebView: WebView
    private lateinit var youtubeControls: LinearLayout
    private lateinit var youtubeFadeButton: TextView
    private lateinit var youtubeFullscreenButton: TextView
    private lateinit var youtubeMinimizeButton: TextView
    private lateinit var youtubeCloseButton: TextView
    private lateinit var carAudioPlayer: View
    private lateinit var carAudioTitle: TextView
    private lateinit var carAudioPrevious: TextView
    private lateinit var carAudioPlayPause: TextView
    private lateinit var carAudioNext: TextView
    private lateinit var carAudioStop: TextView
    private var youtubeFullscreen = false
    private var youtubeFaded = false
    private var youtubeCustomView: View? = null
    private var youtubeCustomCallback: WebChromeClient.CustomViewCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val carAudioRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && ::carAudioPlayer.isInitialized) {
                refreshCarAudioPlayer()
                handler.postDelayed(this, 500L)
            }
        }
    }

    private val youtubeAutoFade = Runnable {
        if (::youtubeOverlay.isInitialized &&
            youtubeOverlay.visibility == View.VISIBLE &&
            !youtubeFullscreen
        ) {
            setYoutubeFaded(true)
        }
    }
    private val remoteMusicIo = Executors.newSingleThreadExecutor()
    private val statusListener: (String) -> Unit = { text -> statusText.text = text }
    private val dataListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) buildTiles()
    }
    private var visibleTileOrder: List<String> = emptyList()
    private var draggingView: View? = null

    private data class FixedTile(val id: String, val label: String, val icon: Int, val featured: Boolean = false, val action: (MainActivity) -> Unit)
    private data class RenderTile(
        val key: String,
        val label: String,
        val icon: Drawable?,
        val featured: Boolean,
        val badgeCount: Int = 0,
        val action: () -> Unit
    )

    private val fixedTiles by lazy {
        listOf(
            FixedTile("theonecar", "The One Car", R.drawable.the_one_logo, true) { activity ->
                DashboardUnreadStore.clear(activity)
                activity.startActivity(Intent(activity, WhatsAppConversationsActivity::class.java))
            },
            FixedTile("notifications", "Meldingen", R.drawable.ic_home_notifications_fancy) { it.startActivity(Intent(it, MessageLogActivity::class.java)) },
            FixedTile("mail", "Mail & Kalender", R.drawable.ic_home_mail_fancy) { it.openMailCalendar() },
            FixedTile("route", "Route", R.drawable.ic_home_route_fancy) { it.startActivity(Intent(it, RouteCarActivity::class.java)) },
            FixedTile("household", "Huishouden", R.drawable.ic_home_household_fancy) { it.startActivity(Intent(it, HouseholdCarActivity::class.java)) },
            FixedTile("music", "Muziek", R.drawable.ic_home_music_fancy) { it.showMusicChooser() },
            FixedTile("radio", "Radio", R.drawable.ic_home_radio_fancy) { it.startActivity(Intent(it, RadioStationsActivity::class.java)) },
            FixedTile("parking", "Parkeren", R.drawable.ic_home_parking_fancy) { it.startActivity(Intent(it, ParkingCarActivity::class.java)) },
            FixedTile("news", "Nieuws", R.drawable.ic_home_news_fancy) { it.startActivity(Intent(it, NewsCarActivity::class.java)) },
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
        carRoot = findViewById(R.id.carRoot)
        youtubeOverlay = findViewById(R.id.youtubeOverlay)
        youtubePlayerSurface = findViewById(R.id.youtubePlayerSurface)
        youtubeWebView = findViewById(R.id.youtubeWebView)
        youtubeControls = findViewById(R.id.youtubeControls)
        youtubeFadeButton = findViewById(R.id.youtubeFadeButton)
        youtubeFullscreenButton = findViewById(R.id.youtubeFullscreenButton)
        youtubeMinimizeButton = findViewById(R.id.youtubeMinimizeButton)
        youtubeCloseButton = findViewById(R.id.youtubeCloseButton)
        carAudioPlayer = findViewById(R.id.carAudioPlayer)
        carAudioTitle = findViewById(R.id.carAudioTitle)
        carAudioPrevious = findViewById(R.id.carAudioPrevious)
        carAudioPlayPause = findViewById(R.id.carAudioPlayPause)
        carAudioNext = findViewById(R.id.carAudioNext)
        carAudioStop = findViewById(R.id.carAudioStop)
        configureCarAudioPlayer()
        configureYoutubeOverlay()
        MessageBus.addStatusListener(statusListener)
        MessageBus.addDataListener(dataListener)
        tileGrid.setOnDragListener { _, event -> handleTileDrag(event) }
        UsbPlaybackService.resumeLastSessionIfNeeded(this)
        buildTiles(); ensurePermissionThenStart(); ensureNotificationPermission(); handler.post(clockTick); UpdateChecker.checkForUpdate(this)
    }

    private fun buildTiles() {
        tileGrid.removeAllViews()
        val hidden = CarTileStore.hidden(this)
        val renderTiles = mutableListOf<RenderTile>()

        fixedTiles.filterNot { hidden.contains(it.id) }.forEach { tile ->
            val badge = if (tile.id == "theonecar") DashboardUnreadStore.count(this) else 0
            renderTiles += RenderTile(
                key = "fixed:${tile.id}",
                label = tile.label,
                icon = ContextCompat.getDrawable(this, tile.icon),
                featured = tile.featured,
                badgeCount = badge,
                action = { cancelStartupGuard(); tile.action(this) }
            )
        }

        val pm = packageManager
        CarTileStore.apps(this).toList().sortedBy { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase(Locale.ROOT) } catch (_: Exception) { pkg }
        }.forEach { pkg ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            val icon = try { buildBadgedAppIcon(pm.getApplicationIcon(pkg)) } catch (_: Exception) { ContextCompat.getDrawable(this, R.drawable.ic_home_add_fancy) }
            renderTiles += RenderTile(
                key = "app:$pkg",
                label = label,
                icon = icon,
                featured = false,
                action = {
                    cancelStartupGuard()
                    if (!launchPackage(pkg)) Toast.makeText(this, "App is niet meer geïnstalleerd", Toast.LENGTH_SHORT).show()
                }
            )
        }

        val byKey = renderTiles.associateBy { it.key }
        visibleTileOrder = CarTileStore.orderedKeys(this, renderTiles.map { it.key })
        visibleTileOrder.mapNotNull { byKey[it] }.forEach { tile ->
            addTile(tile.key, tile.label, tile.icon, tile.featured, tile.badgeCount, tile.action)
        }

        addTile(
            key = null,
            label = "App toevoegen",
            icon = ContextCompat.getDrawable(this, R.drawable.ic_home_add_fancy),
            featured = false,
            badgeCount = 0,
            onClick = { cancelStartupGuard(); startActivity(Intent(this, CarAppPickerActivity::class.java)) }
        )
    }

    private fun addTile(key: String?, label: String, icon: Drawable?, featured: Boolean, badgeCount: Int, onClick: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.view_car_tile, tileGrid, false)
        view.findViewById<TextView>(R.id.tileLabel).text = label
        view.findViewById<ImageView>(R.id.tileIcon).setImageDrawable(icon)
        val root = view.findViewById<View>(R.id.tileRoot)
        if (featured) root.setBackgroundResource(R.drawable.bg_car_tile_featured)
        val badge = view.findViewById<TextView>(R.id.tileBadge)
        if (badgeCount > 0) {
            badge.visibility = View.VISIBLE
            badge.text = if (badgeCount > 99) "99+" else badgeCount.toString()
        } else {
            badge.visibility = View.GONE
        }

        view.tag = key
        view.setOnClickListener { onClick() }
        if (key != null) {
            view.setOnLongClickListener {
                draggingView = view
                view.alpha = 0.48f
                val clip = ClipData.newPlainText("the_one_car_tile", key)
                view.startDragAndDrop(clip, View.DragShadowBuilder(view), key, 0)
                true
            }
        } else {
            view.setOnLongClickListener { false }
        }

        val params = GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(7.dp, 7.dp, 7.dp, 7.dp)
        }
        tileGrid.addView(view, params)
    }

    private fun handleTileDrag(event: DragEvent): Boolean {
        val sourceKey = event.localState as? String ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> sourceKey in visibleTileOrder
            DragEvent.ACTION_DRAG_ENTERED,
            DragEvent.ACTION_DRAG_LOCATION,
            DragEvent.ACTION_DRAG_EXITED -> true
            DragEvent.ACTION_DROP -> {
                val targetKey = findTileKeyAt(event.x.toInt(), event.y.toInt())
                val order = visibleTileOrder.toMutableList()
                val from = order.indexOf(sourceKey)
                if (from >= 0 && targetKey != sourceKey) {
                    val moved = order.removeAt(from)
                    val targetIndex = targetKey?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: order.size
                    order.add(targetIndex.coerceIn(0, order.size), moved)
                    CarTileStore.saveOrder(this, order)
                    visibleTileOrder = order
                    Toast.makeText(this, "Tegelvolgorde opgeslagen", Toast.LENGTH_SHORT).show()
                }
                draggingView?.alpha = 1f
                draggingView = null
                buildTiles()
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                draggingView?.alpha = 1f
                draggingView = null
                true
            }
            else -> true
        }
    }

    private fun findTileKeyAt(x: Int, y: Int): String? {
        for (i in 0 until tileGrid.childCount) {
            val child = tileGrid.getChildAt(i)
            val key = child.tag as? String ?: continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) return key
        }
        return null
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
    private fun configureCarAudioPlayer() {
        carAudioPrevious.setOnClickListener {
            UsbPlaybackService.previous(this)
        }
        carAudioPlayPause.setOnClickListener {
            UsbPlaybackService.toggle(this)
        }
        carAudioNext.setOnClickListener {
            UsbPlaybackService.next(this)
        }
        carAudioStop.setOnClickListener {
            UsbPlaybackService.stop(this)
            carAudioPlayer.postDelayed({ refreshCarAudioPlayer() }, 150L)
        }
    }

    private fun refreshCarAudioPlayer() {
        val state = UsbPlaybackService.snapshot()
        if (!state.hasTrack) {
            carAudioPlayer.visibility = View.GONE
            return
        }

        carAudioPlayer.visibility = View.VISIBLE
        carAudioTitle.text = state.title
        carAudioPlayPause.text =
            if (state.isPlaying) "⏸" else "▶"
    }

    private fun configureYoutubeOverlay() {
        youtubeWebView.settings.javaScriptEnabled = true
        youtubeWebView.settings.domStorageEnabled = true
        youtubeWebView.settings.mediaPlaybackRequiresUserGesture = false
        youtubeWebView.settings.useWideViewPort = true
        youtubeWebView.settings.loadWithOverviewMode = true
        youtubeWebView.settings.setSupportZoom(false)
        youtubeWebView.webViewClient = WebViewClient()
        youtubeWebView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {
                if (view == null || youtubeCustomView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                youtubeCustomView = view
                youtubeCustomCallback = callback
                setYoutubeFullscreen(true)
                youtubeWebView.visibility = View.GONE
                youtubePlayerSurface.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                hideYoutubeCustomView()
            }
        }

        youtubeFadeButton.setOnClickListener {
            setYoutubeFaded(!youtubeFaded)
        }
        youtubeFullscreenButton.setOnClickListener {
            setYoutubeFullscreen(true)
        }
        youtubeMinimizeButton.setOnClickListener {
            hideYoutubeCustomView()
            setYoutubeFullscreen(false)
            setYoutubeFaded(false)
        }
        youtubeCloseButton.setOnClickListener {
            closeYoutubeOverlay()
        }

        youtubePlayerSurface.setOnTouchListener { _, _ ->
            if (!youtubeFaded && !youtubeFullscreen) scheduleYoutubeFade()
            false
        }

        youtubeOverlay.setOnHoverListener { _, event ->
            if (!youtubeFullscreen && event.action == MotionEvent.ACTION_HOVER_ENTER) {
                setYoutubeFaded(true)
            }
            false
        }

        tileGrid.setOnHoverListener { _, event ->
            if (youtubeOverlay.visibility == View.VISIBLE &&
                !youtubeFullscreen &&
                event.action == MotionEvent.ACTION_HOVER_ENTER
            ) {
                setYoutubeFaded(true)
            }
            false
        }
    }

    private fun showYoutubeOverlay(url: String = "https://www.youtube.com/") {
        youtubeOverlay.visibility = View.VISIBLE
        youtubeOverlay.bringToFront()
        window.decorView.keepScreenOn = true
        setYoutubeFullscreen(false)
        setYoutubeFaded(false)

        val current = youtubeWebView.url.orEmpty()
        if (current.isBlank() || current == "about:blank") {
            youtubeWebView.loadUrl(url)
        } else if (
            url.contains("music.youtube.com") &&
            !current.contains("music.youtube.com")
        ) {
            youtubeWebView.loadUrl(url)
        }
    }

    private fun setYoutubeFullscreen(fullscreen: Boolean) {
        youtubeFullscreen = fullscreen
        handler.removeCallbacks(youtubeAutoFade)

        val params = youtubeOverlay.layoutParams as FrameLayout.LayoutParams
        if (fullscreen) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.FILL
            params.setMargins(0, 0, 0, 0)
            youtubeMinimizeButton.visibility = View.VISIBLE
            youtubeFullscreenButton.visibility = View.GONE
            setYoutubeFaded(false)
        } else {
            params.width = 560.dp
            params.height = 330.dp
            params.gravity = Gravity.END or Gravity.BOTTOM
            val margin = 14.dp
            params.setMargins(margin, margin, margin, margin)
            youtubeMinimizeButton.visibility = View.VISIBLE
            youtubeFullscreenButton.visibility = View.VISIBLE
            scheduleYoutubeFade()
        }
        youtubeOverlay.layoutParams = params
        youtubeOverlay.bringToFront()
    }

    private fun setYoutubeFaded(faded: Boolean) {
        if (youtubeFullscreen && faded) return

        youtubeFaded = faded
        handler.removeCallbacks(youtubeAutoFade)
        youtubePlayerSurface.passThrough = faded
        youtubePlayerSurface.animate()
            .alpha(if (faded) 0.20f else 1.0f)
            .setDuration(180L)
            .start()
        youtubeControls.animate()
            .alpha(if (faded) 0.78f else 1.0f)
            .setDuration(180L)
            .start()
        youtubeFadeButton.text = if (faded) "Player" else "Tegels"

        if (!faded && !youtubeFullscreen) scheduleYoutubeFade()
    }

    private fun scheduleYoutubeFade() {
        handler.removeCallbacks(youtubeAutoFade)
        if (!youtubeFullscreen && youtubeOverlay.visibility == View.VISIBLE) {
            handler.postDelayed(youtubeAutoFade, 4500L)
        }
    }

    private fun hideYoutubeCustomView() {
        val custom = youtubeCustomView ?: return
        youtubePlayerSurface.removeView(custom)
        youtubeCustomView = null
        youtubeWebView.visibility = View.VISIBLE
        youtubeCustomCallback?.onCustomViewHidden()
        youtubeCustomCallback = null
    }

    private fun closeYoutubeOverlay() {
        handler.removeCallbacks(youtubeAutoFade)
        hideYoutubeCustomView()
        youtubeWebView.stopLoading()
        youtubeWebView.loadUrl("about:blank")
        youtubeOverlay.visibility = View.GONE
        youtubeFaded = false
        youtubeFullscreen = false
        window.decorView.keepScreenOn = false
    }

    private fun showMusicChooser() {
        AlertDialog.Builder(this)
            .setTitle("Muziek")
            .setItems(arrayOf("📻 Radio", "🗂 Shared Media", "🎧 The One Mixes")) { _, which ->
                when (which) {
                    0 -> openCarRadio()
                    1 -> showSharedMediaChooser()
                    2 -> startActivity(Intent(this, SupremacyMixesActivity::class.java))
                }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun showSharedMediaChooser() {
        AlertDialog.Builder(this)
            .setTitle("Shared Media")
            .setItems(
                arrayOf(
                    "USB / SD op deze Car",
                    "The One Family bibliotheek"
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, UsbMusicActivity::class.java))
                    1 -> openRemoteUsbMusic()
                }
            }
            .setNegativeButton("Terug", null)
            .show()
    }

    private fun openRemoteUsbMusic() {
        loadRemoteUsbCatalog()
    }

    private fun showRemoteUsbPinDialog() {
        val input = EditText(this).apply {
            hint = "Pincode"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = android.view.Gravity.CENTER
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Shared Media")
            .setMessage("Voer de pincode in voor je USB-muziek.")
            .setView(input)
            .setPositiveButton("Openen", null)
            .setNegativeButton("Annuleren", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.isBlank()) return@setOnClickListener

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                remoteMusicIo.execute {
                    val ok = try {
                        RemoteUsbMusicClient.login(this, pin)
                    } catch (_: Exception) {
                        false
                    }

                    runOnUiThread {
                        if (ok) {
                            dialog.dismiss()
                            loadRemoteUsbCatalog()
                        } else {
                            input.text.clear()
                            input.error = "Pincode niet juist of server niet bereikbaar"
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun loadRemoteUsbCatalog() {
        Toast.makeText(this, "Shared Media laden…", Toast.LENGTH_SHORT).show()
        remoteMusicIo.execute {
            try {
                if (!RemoteUsbMusicClient.hasToken(this) &&
                    !RemoteUsbMusicClient.loginForBrowsing(this)
                ) {
                    throw IllegalStateException("Shared Media is tijdelijk niet bereikbaar")
                }

                val sticks = try {
                    RemoteUsbMusicClient.catalog(this)
                } catch (_: RemoteUsbMusicClient.AuthRequired) {
                    RemoteUsbMusicClient.clearToken(this)
                    if (!RemoteUsbMusicClient.loginForBrowsing(this)) {
                        throw IllegalStateException("Shared Media is tijdelijk niet bereikbaar")
                    }
                    RemoteUsbMusicClient.catalog(this)
                }
                runOnUiThread {
                    if (sticks.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("Shared Media")
                            .setMessage("Nog geen gesynchroniseerde USB-muziek gevonden.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        showRemoteStickDialog(sticks)
                    }
                }
            } catch (_: RemoteUsbMusicClient.AuthRequired) {
                RemoteUsbMusicClient.clearToken(this)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Shared Media kon niet opnieuw verbinden.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        e.message ?: "Shared Media kon niet worden geladen",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showRemoteStickDialog(sticks: List<RemoteUsbMusicClient.RemoteStick>) {
        AlertDialog.Builder(this)
            .setTitle("Shared Media")
            .setItems(sticks.map { it.deviceName + " • " + it.stickName }.toTypedArray()) { _, which ->
                showRemoteFolderDialog(sticks[which])
            }
            .setNegativeButton("Sluiten", null)
            .show()
    }

    private fun showRemoteFolderDialog(stick: RemoteUsbMusicClient.RemoteStick) {
        showRemoteFolderLevel(stick, "")
    }

    private fun showRemoteFolderLevel(
        stick: RemoteUsbMusicClient.RemoteStick,
        prefix: String
    ) {
        val normalizedPrefix = prefix.trim('/')
        val childFolders = linkedSetOf<String>()
        val directFiles = mutableListOf<RemoteUsbMusicClient.RemoteFile>()

        stick.files.forEach { file ->
            val folder = file.folder.replace('\\', '/').trim('/')
            when {
                normalizedPrefix.isBlank() && folder.isBlank() -> {
                    directFiles += file
                }
                normalizedPrefix.isBlank() -> {
                    childFolders += folder.substringBefore('/')
                }
                folder == normalizedPrefix -> {
                    directFiles += file
                }
                folder.startsWith("$normalizedPrefix/") -> {
                    val rest = folder.removePrefix("$normalizedPrefix/")
                    val child = rest.substringBefore('/')
                    if (child.isNotBlank()) {
                        childFolders += "$normalizedPrefix/$child"
                    }
                }
            }
        }

        val folders = childFolders.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val sortedFiles = directFiles.sortedBy {
            it.displayName.lowercase(Locale.ROOT)
        }

        if (folders.isEmpty()) {
            showRemoteTrackDialog(
                stick,
                normalizedPrefix.ifBlank { "Hoofdmap" },
                sortedFiles
            )
            return
        }

        val labels = mutableListOf<String>()
        folders.forEach {
            labels += "📁 " + it.substringAfterLast('/')
        }
        if (sortedFiles.isNotEmpty()) {
            labels += "🎵 Nummers in deze map (${sortedFiles.size})"
        }

        val title = if (normalizedPrefix.isBlank()) {
            stick.deviceName + " • " + stick.stickName
        } else {
            normalizedPrefix.substringAfterLast('/')
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < folders.size) {
                    showRemoteFolderLevel(stick, folders[which])
                } else {
                    showRemoteTrackDialog(
                        stick,
                        normalizedPrefix.ifBlank { "Hoofdmap" },
                        sortedFiles
                    )
                }
            }
            .setNegativeButton(
                if (normalizedPrefix.isBlank()) "Sluiten" else "Terug"
            ) { _, _ ->
                if (normalizedPrefix.isNotBlank()) {
                    val parent = normalizedPrefix.substringBeforeLast('/', "")
                    showRemoteFolderLevel(stick, parent)
                }
            }
            .show()
    }

    private fun showRemoteTrackDialog(
        stick: RemoteUsbMusicClient.RemoteStick,
        folder: String,
        files: List<RemoteUsbMusicClient.RemoteFile>
    ) {
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(folder)
                .setMessage("In deze map zijn nog geen afspeelbare nummers gecachet.")
                .setNegativeButton("Terug") { _, _ ->
                    val parent = folder
                        .takeUnless { it == "Hoofdmap" }
                        ?.substringBeforeLast('/', "")
                        .orEmpty()
                    showRemoteFolderLevel(stick, parent)
                }
                .show()
            return
        }

        val listView = android.widget.ListView(this)
        listView.divider = android.graphics.drawable.ColorDrawable(
            ContextCompat.getColor(this, R.color.line)
        )
        listView.dividerHeight = 1

        listView.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = files.size
            override fun getItem(position: Int): Any = files[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup?
            ): View {
                val file = files[position]

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12.dp, 10.dp, 12.dp, 10.dp)
                    layoutParams = android.widget.AbsListView.LayoutParams(
                        android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                        android.widget.AbsListView.LayoutParams.WRAP_CONTENT
                    )
                }

                row.addView(
                    TextView(this@MainActivity).apply {
                        text = cleanRemoteUsbTrackTitle(file.displayName)
                        textSize = 16f
                        // De standaard AlertDialog van deze K2401-ROM is licht.
                        // Donkere tekst voorkomt dat titels wit-op-wit verdwijnen.
                        setTextColor(android.graphics.Color.parseColor("#101925"))
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(8, 14, 14, 14)
                        setOnClickListener {
                            playRemoteUsbFolder(files, position)
                        }
                    },
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )

                row.addView(
                    TextView(this@MainActivity).apply {
                        text = "↓"
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setTextColor(
                            android.graphics.Color.parseColor("#D8A451")
                        )
                        contentDescription = "Download ${file.name}"
                        setBackgroundResource(R.drawable.bg_gold_outline)
                        setPadding(16, 12, 16, 12)
                        setOnClickListener {
                            requestRemoteUsbDownload(file)
                        }
                    },
                    LinearLayout.LayoutParams(
                        58.dp,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = 10.dp
                        marginEnd = 12.dp
                    }
                )

                row.addView(
                    TextView(this@MainActivity).apply {
                        text = "▶"
                        textSize = 19f
                        gravity = Gravity.CENTER
                        setTextColor(android.graphics.Color.WHITE)
                        contentDescription = "Speel ${file.name} af"
                        setBackgroundResource(R.drawable.bg_outline)
                        setPadding(16, 12, 16, 12)
                        setOnClickListener {
                            playRemoteUsbFolder(files, position)
                        }
                    },
                    LinearLayout.LayoutParams(
                        62.dp,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )

                return row
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(folder)
            .setView(listView)
            .setNegativeButton("Terug") { _, _ ->
                val parent = folder
                    .takeUnless { it == "Hoofdmap" }
                    ?.substringBeforeLast('/', "")
                    .orEmpty()
                showRemoteFolderLevel(stick, parent)
            }
            .create()

        dialog.setOnShowListener {
            listView.layoutParams = listView.layoutParams?.apply {
                height = (420.dp).coerceAtMost(
                    resources.displayMetrics.heightPixels - 160.dp
                )
            }
        }
        dialog.show()
    }

    private fun requestRemoteUsbDownload(file: RemoteUsbMusicClient.RemoteFile) {
        val input = EditText(this).apply {
            hint = "Pincode"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = android.view.Gravity.CENTER
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("USB-download beveiligen")
            .setMessage("Voer je pincode in om dit nummer te downloaden.")
            .setView(input)
            .setPositiveButton("Downloaden", null)
            .setNegativeButton("Annuleren", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.isBlank()) return@setOnClickListener

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                remoteMusicIo.execute {
                    val valid = try {
                        RemoteUsbMusicClient.login(this, pin)
                    } catch (_: Exception) {
                        false
                    }

                    runOnUiThread {
                        if (valid) {
                            dialog.dismiss()
                            enqueueRemoteUsbDownload(file)
                        } else {
                            input.text.clear()
                            input.error = "Pincode niet juist"
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun enqueueRemoteUsbDownload(file: RemoteUsbMusicClient.RemoteFile) {
        try {
            val safeName = file.name
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .ifBlank { "TheOne-nummer.mp3" }
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(
                Uri.parse(RemoteUsbMusicClient.streamUrl(this, file))
            )
                .setTitle(safeName)
                .setDescription("The One Car • Shared Media")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "The One/$safeName"
                )
            manager.enqueue(request)
            Toast.makeText(this, "Download gestart: $safeName", Toast.LENGTH_SHORT).show()
        } catch (_: RemoteUsbMusicClient.AuthRequired) {
            Toast.makeText(this, "Pincode opnieuw invoeren om te downloaden.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Download starten mislukt", Toast.LENGTH_LONG).show()
        }
    }

    private fun cleanRemoteUsbTrackTitle(raw: String): String =
        raw
            .replace(
                Regex("\\.(mp3|wma|m4a|aac|flac|ogg|oga|opus|wav|mp4)$", RegexOption.IGNORE_CASE),
                ""
            )
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun playRemoteUsbFolder(
        files: List<RemoteUsbMusicClient.RemoteFile>,
        index: Int
    ) {
        try {
            val queue = files.map {
                UsbPlaybackService.QueueItem(
                    RemoteUsbMusicClient.streamUrl(this, it),
                    cleanRemoteUsbTrackTitle(it.displayName)
                )
            }
            UsbPlaybackService.play(this, queue, index)
            Toast.makeText(this, "Shared Media speelt af", Toast.LENGTH_SHORT).show()
        } catch (_: RemoteUsbMusicClient.AuthRequired) {
            RemoteUsbMusicClient.clearToken(this)
            remoteMusicIo.execute {
                val ok = try { RemoteUsbMusicClient.loginForBrowsing(this) } catch (_: Exception) { false }
                runOnUiThread {
                    if (ok) {
                        playRemoteUsbFolder(files, index)
                    } else {
                        Toast.makeText(this, "Shared Media kon niet opnieuw verbinden.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Afspelen mislukt", Toast.LENGTH_LONG).show()
        }
    }

    /** Open de fabrieksradio van de head-unit, niet de streamingradio van The One. */
    private fun openCarRadio() {
        val knownRadioPackages = listOf(
            "com.android.fmradio",
            "com.mediatek.fmradio",
            "com.syu.radio",
            "com.microntek.radio",
            "com.navimods.radio",
            "com.zjinnova.radio",
            "com.ts.radio"
        )
        knownRadioPackages.forEach { if (launchPackage(it)) return }

        // K2401-ROMs gebruiken verschillende package-namen. Zoek daarom ook
        // naar de geïnstalleerde launcher-activity die als Radio/FM wordt getoond.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidate = try {
            packageManager.queryIntentActivities(launcherIntent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { info ->
                    val label = info.loadLabel(packageManager).toString()
                    Triple(info, label, (label + " " + info.activityInfo.packageName + " " + info.activityInfo.name).lowercase(Locale.ROOT))
                }
                .filter { (_, _, haystack) ->
                    haystack.contains("radio") || haystack.contains("fmradio") || haystack.contains("fm radio")
                }
                .sortedBy { (_, label, _) -> if (label.equals("Radio", true) || label.equals("FM Radio", true)) 0 else 1 }
                .firstOrNull()?.first
        } catch (_: Exception) { null }

        if (candidate != null) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(candidate.activityInfo.packageName, candidate.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            } catch (_: Exception) {}
        }

        Toast.makeText(this, "De radio-app van deze head-unit kon niet worden gevonden", Toast.LENGTH_LONG).show()
    }

    /** Zelf toegevoegde apps krijgen dezelfde luxe badge als op de telefoon. */
    private fun buildBadgedAppIcon(appIcon: Drawable): Drawable {
        val badge = ContextCompat.getDrawable(this, R.drawable.bg_home_tile_badge)!!.mutate()
        val layered = LayerDrawable(arrayOf(badge, appIcon))
        val inset = (13 * resources.displayMetrics.density).toInt()
        layered.setLayerInset(1, inset, inset, inset, inset)
        return layered
    }
    private fun launchPackage(pkg: String): Boolean {
        when (pkg.lowercase(Locale.ROOT)) {
            "com.google.android.youtube" -> {
                showYoutubeOverlay("https://www.youtube.com/")
                return true
            }
            "com.google.android.apps.youtube.music" -> {
                showYoutubeOverlay("https://music.youtube.com/")
                return true
            }
        }

        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { startActivity(intent); true } catch (_: Exception) { false }
    }
    private fun cancelStartupGuard() { try { startService(Intent(this, BluetoothListenerService::class.java).apply { action = BluetoothListenerService.ACTION_CANCEL_STARTUP }) } catch (_: Exception) {} }
    override fun onUserInteraction() { super.onUserInteraction(); cancelStartupGuard() }
    override fun onResume() {
        super.onResume()
        statusText.text = MessageBus.currentStatus()
        buildTiles()
        handler.removeCallbacks(carAudioRefresh)
        carAudioRefresh.run()
    }

    override fun onPause() {
        handler.removeCallbacks(carAudioRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        remoteMusicIo.shutdownNow()
        MessageBus.removeStatusListener(statusListener)
        MessageBus.removeDataListener(dataListener)
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(carAudioRefresh)
        handler.removeCallbacks(youtubeAutoFade)
        if (::youtubeWebView.isInitialized) {
            youtubeWebView.stopLoading()
            youtubeWebView.destroy()
        }
        super.onDestroy()
    }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
