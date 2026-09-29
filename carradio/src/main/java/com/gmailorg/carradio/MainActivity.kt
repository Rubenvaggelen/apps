package com.gmailorg.carradio

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.os.Looper
import android.os.Environment
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
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
import android.widget.SeekBar
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
    private lateinit var carVolumeDown: TextView
    private lateinit var carVolumeUp: TextView
    private lateinit var carVolumeSeek: SeekBar
    private lateinit var audioManager: AudioManager
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
    private val favoriteUsbKeys = linkedSetOf<String>()
    private val statusListener: (String) -> Unit = { text -> statusText.text = text }
    private val dataListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) buildTiles()
    }
    private var visibleTileOrder: List<String> = emptyList()
    private var draggingView: View? = null
    private var currentFamilyStick: RemoteUsbMusicClient.RemoteStick? = null
    private var currentFamilyFiles: List<RemoteUsbMusicClient.RemoteFile> = emptyList()
    private var carPersonDialogShowing = false

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
        carVolumeDown = findViewById(R.id.carVolumeDown)
        carVolumeUp = findViewById(R.id.carVolumeUp)
        carVolumeSeek = findViewById(R.id.carVolumeSeek)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        configureCarVolume()
        configureCarAudioPlayer()
        configureYoutubeOverlay()
        MessageBus.addStatusListener(statusListener)
        MessageBus.addDataListener(dataListener)
        tileGrid.setOnDragListener { _, event -> handleTileDrag(event) }
        UsbPlaybackService.resumeLastSessionIfNeeded(this)
        ensureCarPersonRegistration()
        buildTiles(); ensurePermissionThenStart(); ensureNotificationPermission(); handler.post(clockTick); UpdateChecker.checkForUpdate(this)
        if (intent?.getBooleanExtra(CarPlayerActivity.EXTRA_OPEN_MUSIC, false) == true) {
            intent.removeExtra(CarPlayerActivity.EXTRA_OPEN_MUSIC)
            carRoot.post { showMusicChooser() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(CarPlayerActivity.EXTRA_OPEN_MUSIC, false) == true) {
            intent.removeExtra(CarPlayerActivity.EXTRA_OPEN_MUSIC)
            carRoot.post { showMusicChooser() }
        }
    }

    private fun ensureCarPersonRegistration() {
        if (CarFamilyAccess.hasPersonName(this)) {
            remoteMusicIo.execute { runCatching { CarFamilyAccess.heartbeat(this) } }
            return
        }
        if (carPersonDialogShowing || isFinishing || isDestroyed) return
        carPersonDialogShowing = true

        val input = EditText(this).apply {
            hint = "Jouw naam"
            isSingleLine = true
            maxLines = 1
            setPadding(24, 14, 24, 14)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Wie gebruikt The One Car?")
            .setMessage(
                "Vul één keer je naam in. Deze naam wordt aan deze Car gekoppeld " +
                    "zodat The One kan zien van wie dit apparaat is."
            )
            .setView(input)
            .setPositiveButton("Opslaan", null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val personName = input.text.toString().trim()
                if (personName.length < 2) {
                    input.error = "Vul je naam in"
                    return@setOnClickListener
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                CarFamilyAccess.savePersonName(this, personName)

                remoteMusicIo.execute {
                    val ok = runCatching {
                        CarFamilyAccess.heartbeat(this)
                    }.isSuccess
                    runOnUiThread {
                        if (ok) {
                            dialog.dismiss()
                            Toast.makeText(
                                this,
                                "$personName is gekoppeld aan The One Car • alle mediarechten actief",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            input.error = "Registreren mislukt. Probeer opnieuw."
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            carPersonDialogShowing = false
        }
        dialog.show()
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
    private fun configureCarVolume() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        carVolumeSeek.max = maxVolume.coerceAtLeast(1)
        refreshCarVolume()

        carVolumeDown.setOnClickListener {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            refreshCarVolume()
        }

        carVolumeUp.setOnClickListener {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            refreshCarVolume()
        }

        carVolumeSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            progress,
                            AudioManager.FLAG_SHOW_UI
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    refreshCarVolume()
                }
            }
        )
    }

    private fun refreshCarVolume() {
        if (!::audioManager.isInitialized || !::carVolumeSeek.isInitialized) return
        carVolumeSeek.progress =
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun openCarPlayer() {
        if (!UsbPlaybackService.snapshot().hasTrack) return
        startActivity(
            Intent(this, CarPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
        )
    }

    private fun configureCarAudioPlayer() {
        carAudioTitle.setOnClickListener {
            openCurrentMusicTrack()
        }
        carAudioPlayer.setOnClickListener {
            openCarPlayer()
        }

        carAudioPrevious.setOnClickListener {
            UsbPlaybackService.previous(this)
            refreshCarAudioPlayer()
        }
        carAudioPlayPause.setOnClickListener {
            UsbPlaybackService.toggle(this)
            refreshCarAudioPlayer()
        }
        carAudioNext.setOnClickListener {
            UsbPlaybackService.next(this)
            refreshCarAudioPlayer()
        }
        carAudioStop.setOnClickListener {
            UsbPlaybackService.stop(this)
            carAudioPlayer.postDelayed({ refreshCarAudioPlayer() }, 150L)
        }
    }

    private fun openCurrentMusicTrack() {
        val state = UsbPlaybackService.snapshot()
        if (!state.hasTrack) return

        val activeUri = state.uri.orEmpty()
        val isSharedMedia =
            activeUri.contains("the-one-remote-api", ignoreCase = true) ||
                activeUri.contains("music.php", ignoreCase = true)
        if (!isSharedMedia) {
            startActivity(
                Intent(this, SupremacyMixesActivity::class.java)
                    .putExtra("focus_title", state.title)
            )
            return
        }

        fun openFrom(
            stick: RemoteUsbMusicClient.RemoteStick,
            files: List<RemoteUsbMusicClient.RemoteFile>
        ): Boolean {
            val active = files.firstOrNull { file ->
                runCatching {
                    RemoteUsbMusicClient.streamUrl(this, file) == state.uri
                }.getOrDefault(false)
            } ?: files.firstOrNull {
                state.title.equals(
                    cleanRemoteUsbTrackTitle(it.displayName),
                    ignoreCase = true
                )
            } ?: return false

            currentFamilyStick = stick
            currentFamilyFiles = files
            showRemoteFolderLevel(stick, active.folder)
            return true
        }

        val current = currentFamilyStick
        if (current != null && openFrom(current, currentFamilyFiles)) return

        remoteMusicIo.execute {
            val sticks = try {
                RemoteUsbMusicClient.catalog(this)
            } catch (_: RemoteUsbMusicClient.AuthRequired) {
                val ok = try {
                    RemoteUsbMusicClient.loginForBrowsing(this)
                } catch (_: Exception) {
                    false
                }
                if (!ok) emptyList() else try {
                    RemoteUsbMusicClient.catalog(this)
                } catch (_: Exception) {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

            val match = sticks.firstNotNullOfOrNull { stick ->
                val file = stick.files.firstOrNull {
                    state.title.equals(
                        cleanRemoteUsbTrackTitle(it.displayName),
                        ignoreCase = true
                    )
                }
                if (file == null) null else stick to file
            }

            runOnUiThread {
                if (match != null) {
                    val (stick, file) = match
                    currentFamilyStick = stick
                    currentFamilyFiles = stick.files
                    showRemoteFolderLevel(stick, file.folder)
                } else {
                    Toast.makeText(
                        this,
                        "Map van het spelende nummer kon niet worden gevonden.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
        val familyTrack = currentFamilyStick != null &&
            currentFamilyFiles.any {
                state.title.equals(cleanRemoteUsbTrackTitle(it.displayName), ignoreCase = true)
            }
        carAudioTitle.paintFlags =
            carAudioTitle.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        carAudioTitle.contentDescription =
            if (familyTrack) {
                "Tik om naar de map van het spelende nummer te gaan"
            } else {
                "Tik om het spelende nummer in The One Mixes te openen"
            }
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

    private fun openTheOneFavorites() {
        Toast.makeText(this, "The One Favorites laden…", Toast.LENGTH_SHORT).show()
        remoteMusicIo.execute {
            try {
                if (!RemoteUsbMusicClient.hasToken(this) &&
                    !RemoteUsbMusicClient.loginForBrowsing(this)
                ) {
                    throw IllegalStateException("The One Family is tijdelijk niet bereikbaar")
                }

                val favorites = RemoteUsbMusicClient.favorites(this)
                val playable = favorites.mapNotNull { item ->
                    when {
                        item.kind.equals("mix", ignoreCase = true) &&
                            item.url.isNotBlank() ->
                            Triple(item, item.url, item.title)

                        item.kind.equals("usb", ignoreCase = true) &&
                            item.deviceId.isNotBlank() &&
                            item.stickId.isNotBlank() &&
                            item.path.isNotBlank() ->
                            Triple(
                                item,
                                RemoteUsbMusicClient.streamUrl(this, item),
                                cleanRemoteUsbTrackTitle(item.title)
                            )

                        else -> null
                    }
                }

                runOnUiThread {
                    showTheOneFavoritesDialog(favorites, playable)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        e.message ?: "The One Favorites konden niet worden geladen",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showTheOneFavoritesDialog(
        favorites: List<RemoteUsbMusicClient.FavoriteItem>,
        playable: List<Triple<RemoteUsbMusicClient.FavoriteItem, String, String>>
    ) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 22.dp, 28.dp, 20.dp)
            setBackgroundResource(R.drawable.bg_player_panel)
        }

        panel.addView(TextView(this).apply {
            text = "THE ONE FAMILY • MUZIEK"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
        })
        panel.addView(TextView(this).apply {
            text = "★ The One Favorites"
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 5.dp, 0, 3.dp)
        })
        panel.addView(TextView(this).apply {
            text = if (favorites.isEmpty()) "Nog geen favorieten"
            else "${favorites.size} favoriet" + if (favorites.size == 1) "" else "en"
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#91A4BD"))
            setPadding(0, 0, 0, 15.dp)
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        favorites.forEach { item ->
            val playableEntry = playable.firstOrNull { it.first.id == item.id }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.dp, 14.dp, 16.dp, 14.dp)
                setBackgroundResource(R.drawable.bg_outline)
            }
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(TextView(this).apply {
                text = item.title
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    android.graphics.Color.parseColor(
                        if (playableEntry != null) "#FFFFFF" else "#8F9BAD"
                    )
                )
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            copy.addView(TextView(this).apply {
                text = item.sourceLabel.ifBlank {
                    if (item.kind.equals("mix", true)) "The One Mixes" else "Shared Media"
                } + if (playableEntry == null) " • Bron niet beschikbaar" else ""
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#91A4BD"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 4.dp, 0, 0)
            })
            row.addView(
                copy,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            row.addView(TextView(this).apply {
                text = "★"
                textSize = 31f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#D8A451"))
                setPadding(12.dp, 10.dp, 12.dp, 10.dp)
                setOnClickListener {
                    isEnabled = false
                    remoteMusicIo.execute {
                        val allowed = try {
                            CarFamilyAccess.refreshMusicRights(this@MainActivity)
                        } catch (_: Exception) {
                            false
                        }
                        val ok = if (allowed) try {
                            RemoteUsbMusicClient.setFavoriteItem(
                                this@MainActivity,
                                item,
                                false
                            )
                            true
                        } catch (_: Exception) {
                            false
                        } else false

                        runOnUiThread {
                            if (!allowed) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Dit apparaat heeft geen muziekrechten. Geef deze eerst via Main.",
                                    Toast.LENGTH_LONG
                                ).show()
                                isEnabled = true
                            } else if (ok) {
                                dialog.dismiss()
                                openTheOneFavorites()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Favoriet verwijderen mislukt",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isEnabled = true
                            }
                        }
                    }
                }
            })

            row.addView(TextView(this).apply {
                text = if (playableEntry != null) "▶" else "…"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(
                    android.graphics.Color.parseColor(
                        if (playableEntry != null) "#20B8FF" else "#8F9BAD"
                    )
                )
                setPadding(12.dp, 10.dp, 8.dp, 10.dp)
                setOnClickListener {
                    val selected = playableEntry ?: return@setOnClickListener
                    val queue = playable.map {
                        UsbPlaybackService.QueueItem(it.second, it.third)
                    }
                    val index =
                        playable.indexOfFirst { it.first.id == selected.first.id }
                            .coerceAtLeast(0)
                    UsbPlaybackService.play(this@MainActivity, queue, index)
                    dialog.dismiss()
                    openCarPlayer()
                    carAudioPlayer.postDelayed({ refreshCarAudioPlayer() }, 120L)
                }
            })

            if (playableEntry != null) {
                row.setOnClickListener {
                    val queue = playable.map {
                        UsbPlaybackService.QueueItem(it.second, it.third)
                    }
                    val index =
                        playable.indexOfFirst { it.first.id == playableEntry.first.id }
                            .coerceAtLeast(0)
                    UsbPlaybackService.play(this@MainActivity, queue, index)
                    dialog.dismiss()
                    openCarPlayer()
                    carAudioPlayer.postDelayed({ refreshCarAudioPlayer() }, 120L)
                }
            }

            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 11.dp }
            )
        }

        if (favorites.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Markeer bij Shared Media of The One Mixes een nummer met ☆ om het hier toe te voegen."
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#91A4BD"))
                setPadding(18.dp, 30.dp, 18.dp, 30.dp)
            })
        }

        panel.addView(
            android.widget.ScrollView(this).apply {
                isFillViewport = true
                addView(list)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        panel.addView(TextView(this).apply {
            text = "SLUITEN"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
            setBackgroundResource(R.drawable.bg_gold_outline)
            setPadding(18.dp, 13.dp, 18.dp, 13.dp)
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            (resources.displayMetrics.heightPixels * 0.86f).toInt()
        )
    }

    private fun showMusicChooser() {
        AlertDialog.Builder(this)
            .setTitle("Muziek")
            .setItems(arrayOf("📻 Radio", "★ The One Favorites", "🗂 Shared Media", "🎧 The One Mixes")) { _, which ->
                when (which) {
                    0 -> openCarRadio()
                    1 -> openTheOneFavorites()
                    2 -> showSharedMediaChooser()
                    3 -> startActivity(Intent(this, SupremacyMixesActivity::class.java))
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
                val favorites = try {
                    RemoteUsbMusicClient.favorites(this)
                } catch (_: Exception) {
                    emptyList()
                }
                favoriteUsbKeys.clear()
                favorites
                    .filter { it.kind.equals("usb", ignoreCase = true) }
                    .forEach {
                        favoriteUsbKeys += usbFavoriteKey(it.deviceId, it.stickId, it.path)
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

    private fun remoteStickDisplayName(stick: RemoteUsbMusicClient.RemoteStick): String {
        val isPrimaryRubenMusic =
            stick.deviceName.equals("Surface", ignoreCase = true) &&
                stick.stickName.equals("Ruben music", ignoreCase = true)
        val isRubenFallback =
            stick.deviceName.equals("Ruben", ignoreCase = true) &&
                (stick.stickName.equals("Ruben", ignoreCase = true) ||
                    stick.stickName.equals("Ruben music", ignoreCase = true))

        return if (isPrimaryRubenMusic || isRubenFallback) {
            "Ruben music"
        } else {
            stick.deviceName + " • " + stick.stickName
        }
    }

    private fun showRemoteStickDialog(sticks: List<RemoteUsbMusicClient.RemoteStick>) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 22.dp, 28.dp, 20.dp)
            setBackgroundResource(R.drawable.bg_player_panel)
        }

        panel.addView(
            TextView(this).apply {
                text = "THE ONE FAMILY • CAR"
                textSize = 13f
                letterSpacing = 0.18f
                setTextColor(android.graphics.Color.parseColor("#D8A451"))
            }
        )
        panel.addView(
            TextView(this).apply {
                text = "Shared Media"
                textSize = 30f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 4.dp, 0, 2.dp)
            }
        )
        panel.addView(
            TextView(this).apply {
                text = "Kies een bron"
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#91A4BD"))
                setPadding(0, 0, 0, 18.dp)
            }
        )

        val sourceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(sourceList)
        }
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        sticks.forEach { stick ->
            val cached = stick.files.count { it.cached }
            val total = stick.totalFiles
            val status = if (cached >= total && total > 0) {
                "$cached nummer" + if (cached == 1) "" else "s"
            } else {
                "$cached van $total beschikbaar"
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20.dp, 16.dp, 18.dp, 16.dp)
                setBackgroundResource(R.drawable.bg_outline)
                isClickable = true
                isFocusable = true
            }
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            copy.addView(
                TextView(this).apply {
                    text = remoteStickDisplayName(stick)
                    textSize = 21f
                    setTextColor(android.graphics.Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
            copy.addView(
                TextView(this).apply {
                    text = status
                    textSize = 15f
                    setTextColor(android.graphics.Color.parseColor("#91A4BD"))
                    setPadding(0, 5.dp, 0, 0)
                }
            )
            card.addView(
                copy,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            card.addView(
                TextView(this).apply {
                    text = "›"
                    textSize = 34f
                    gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.parseColor("#D8A451"))
                    setPadding(14.dp, 0, 2.dp, 0)
                }
            )
            card.setOnClickListener {
                dialog.dismiss()
                if (stick.files.none { it.cached }) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(remoteStickDisplayName(stick))
                        .setMessage(
                            "Deze bron wordt nog gesynchroniseerd. " +
                                "Er zijn nog geen nummers klaar om af te spelen."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showRemoteFolderDialog(stick)
                }
            }

            sourceList.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12.dp
                }
            )
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 0)
        }
        val close = TextView(this).apply {
            text = "SLUITEN"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
            setBackgroundResource(R.drawable.bg_gold_outline)
            setPadding(20.dp, 13.dp, 20.dp, 13.dp)
            setOnClickListener { dialog.dismiss() }
        }
        val refresh = TextView(this).apply {
            text = "VERNIEUWEN"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#201505"))
            setBackgroundResource(R.drawable.bg_amber_button)
            setPadding(20.dp, 13.dp, 20.dp, 13.dp)
            setOnClickListener {
                dialog.dismiss()
                loadRemoteUsbCatalog()
            }
        }
        actions.addView(
            close,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dp
            }
        )
        actions.addView(
            refresh,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8.dp
            }
        )
        panel.addView(actions)

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.72f }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.84f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    private fun showRemoteFolderDialog(stick: RemoteUsbMusicClient.RemoteStick) {
        val firstSegments = stick.files
            .filter { it.cached }
            .map { it.folder.replace('\\', '/').trim('/') }
            .filter { it.isNotBlank() }
            .map { it.substringBefore('/') }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val startFolder = if (firstSegments.size == 1) {
            firstSegments.first()
        } else {
            ""
        }

        showRemoteFolderLevel(stick, startFolder)
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
                normalizedPrefix.isBlank() && folder.isBlank() -> { directFiles += file }
                normalizedPrefix.isBlank() -> { childFolders += folder.substringBefore('/') }
                folder == normalizedPrefix -> { directFiles += file }
                folder.startsWith("$normalizedPrefix/") -> {
                    val rest = folder.removePrefix("$normalizedPrefix/")
                    val child = rest.substringBefore('/')
                    if (child.isNotBlank()) childFolders += "$normalizedPrefix/$child"
                }
            }
        }

        val folders = childFolders.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val sortedFiles = directFiles.sortedBy { it.displayName.lowercase(Locale.ROOT) }

        if (folders.isEmpty()) {
            showRemoteTrackDialog(stick, normalizedPrefix.ifBlank { "Hoofdmap" }, sortedFiles)
            return
        }

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 22.dp, 28.dp, 20.dp)
            setBackgroundResource(R.drawable.bg_player_panel)
        }

        panel.addView(TextView(this).apply {
            text = "THE ONE FAMILY • SHARED MEDIA"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
        })
        panel.addView(TextView(this).apply {
            text = if (normalizedPrefix.isBlank()) remoteStickDisplayName(stick) else normalizedPrefix.substringAfterLast('/')
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 5.dp, 0, 4.dp)
        })
        panel.addView(TextView(this).apply {
            text = if (normalizedPrefix.isBlank()) "Mappen" else normalizedPrefix
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#91A4BD"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(0, 0, 0, 15.dp)
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        folders.forEach { folderPath ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.dp, 14.dp, 16.dp, 14.dp)
                setBackgroundResource(R.drawable.bg_outline)
                isClickable = true
                isFocusable = true
            }
            card.addView(TextView(this).apply {
                text = "▣"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#20B8FF"))
            }, LinearLayout.LayoutParams(42.dp, LinearLayout.LayoutParams.WRAP_CONTENT))
            card.addView(TextView(this).apply {
                text = folderPath.substringAfterLast('/')
                textSize = 22f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(TextView(this).apply {
                text = "›"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#D8A451"))
                setPadding(12.dp, 0, 0, 0)
            })
            card.setOnClickListener {
                dialog.dismiss()
                showRemoteFolderLevel(stick, folderPath)
            }
            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 11.dp })
        }

        if (sortedFiles.isNotEmpty()) {
            val trackCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.dp, 14.dp, 16.dp, 14.dp)
                setBackgroundResource(R.drawable.bg_outline)
                isClickable = true
                isFocusable = true
            }
            trackCard.addView(TextView(this).apply {
                text = "♪"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#20B8FF"))
            }, LinearLayout.LayoutParams(42.dp, LinearLayout.LayoutParams.WRAP_CONTENT))
            trackCard.addView(TextView(this).apply {
                text = "Nummers in deze map\n" + sortedFiles.count { it.cached } + " beschikbaar"
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 2
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            trackCard.addView(TextView(this).apply {
                text = "›"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#D8A451"))
            })
            trackCard.setOnClickListener {
                dialog.dismiss()
                showRemoteTrackDialog(stick, normalizedPrefix.ifBlank { "Hoofdmap" }, sortedFiles)
            }
            list.addView(trackCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 11.dp })
        }

        panel.addView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12.dp, 0, 0)
        }
        val back = TextView(this).apply {
            text = if (normalizedPrefix.isBlank()) "SLUITEN" else "← 1 STAP TERUG"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
            setBackgroundResource(R.drawable.bg_gold_outline)
            setPadding(14.dp, 13.dp, 14.dp, 13.dp)
            setOnClickListener {
                dialog.dismiss()
                if (normalizedPrefix.isNotBlank()) {
                    showRemoteFolderLevel(stick, normalizedPrefix.substringBeforeLast('/', ""))
                }
            }
        }
        val player = TextView(this).apply {
            text = "NAAR PLAYER  ›"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#201505"))
            setBackgroundResource(R.drawable.bg_amber_button)
            setPadding(14.dp, 13.dp, 14.dp, 13.dp)
            setOnClickListener { dialog.dismiss() }
        }
        actions.addView(back, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = 8.dp })
        actions.addView(player, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = 8.dp })
        panel.addView(actions)

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.72f }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            (resources.displayMetrics.heightPixels * 0.86f).toInt()
        )
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
                .setPositiveButton("NAAR PLAYER", null)
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

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 22.dp, 28.dp, 20.dp)
            setBackgroundResource(R.drawable.bg_player_panel)
        }

        panel.addView(TextView(this).apply {
            text = "THE ONE FAMILY • SHARED MEDIA"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
        })
        panel.addView(TextView(this).apply {
            text = folder
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 5.dp, 0, 3.dp)
        })
        panel.addView(TextView(this).apply {
            text = "${files.count { it.cached }} nummer" +
                if (files.count { it.cached } == 1) "" else "s" +
                " beschikbaar"
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#91A4BD"))
            setPadding(0, 0, 0, 15.dp)
        })

        val listView = android.widget.ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(
                android.graphics.Color.parseColor("#24364A")
            )
            dividerHeight = 1
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
        }

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = files.size
            override fun getItem(position: Int): Any = files[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup?
            ): View {
                val file = files[position]
                val isCached = file.cached
                val isCurrent = isRemoteUsbTrackCurrent(file)

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16.dp, 11.dp, 14.dp, 11.dp)
                    setBackgroundResource(
                        if (isCurrent) R.drawable.bg_gold_outline
                        else R.drawable.bg_outline
                    )
                    contentDescription =
                        if (isCurrent) "Nu actief: ${cleanRemoteUsbTrackTitle(file.displayName)}"
                        else cleanRemoteUsbTrackTitle(file.displayName)
                    layoutParams = android.widget.AbsListView.LayoutParams(
                        android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                        android.widget.AbsListView.LayoutParams.WRAP_CONTENT
                    )
                }

                row.addView(
                    TextView(this@MainActivity).apply {
                        text =
                            (if (isCurrent) "▶ NU • " else "") +
                                cleanRemoteUsbTrackTitle(file.displayName) +
                                if (isCached) "" else "  •  Synchroniseren…"
                        textSize = 21f
                        setTextColor(
                            android.graphics.Color.parseColor(
                                when {
                                    !isCached -> "#71839A"
                                    isCurrent -> "#FFD47A"
                                    else -> "#F5F8FC"
                                }
                            )
                        )
                        setTypeface(
                            null,
                            if (isCurrent) android.graphics.Typeface.BOLD
                            else android.graphics.Typeface.NORMAL
                        )
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(4.dp, 12.dp, 12.dp, 12.dp)
                        setOnClickListener {
                            if (isCached) {
                                playRemoteUsbFolder(stick, files, position)
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Dit nummer wordt nog gesynchroniseerd.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
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
                        text = if (favoriteUsbKeys.contains(usbFavoriteKey(file))) "★" else "☆"
                        textSize = 29f
                        gravity = Gravity.CENTER
                        setTextColor(android.graphics.Color.parseColor("#D8A451"))
                        contentDescription = "Favoriet ${file.name}"
                        setPadding(10.dp, 9.dp, 10.dp, 9.dp)
                        setOnClickListener { toggleUsbFavorite(stick, file, this) }
                    },
                    LinearLayout.LayoutParams(
                        52.dp,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 8.dp }
                )

                row.addView(
                    TextView(this@MainActivity).apply {
                        text = if (isCached) "↓" else "…"
                        textSize = 27f
                        gravity = Gravity.CENTER
                        setTextColor(android.graphics.Color.parseColor("#D8A451"))
                        contentDescription = "Download ${file.name}"
                        setBackgroundResource(R.drawable.bg_gold_outline)
                        setPadding(13.dp, 10.dp, 13.dp, 10.dp)
                        alpha = if (isCached) 1f else 0.35f
                        setOnClickListener {
                            if (isCached) {
                                requestRemoteUsbDownload(file)
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Download beschikbaar zodra synchronisatie klaar is.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    LinearLayout.LayoutParams(
                        54.dp,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = 6.dp
                        marginEnd = 10.dp
                    }
                )

                row.addView(
                    TextView(this@MainActivity).apply {
                        text = if (isCached) "▶" else "…"
                        textSize = 27f
                        gravity = Gravity.CENTER
                        setTextColor(
                            android.graphics.Color.parseColor(
                                if (isCurrent) "#D8A451" else "#20B8FF"
                            )
                        )
                        contentDescription = "Speel ${file.name} af"
                        setBackgroundResource(
                            if (isCurrent) R.drawable.bg_gold_outline
                            else R.drawable.bg_outline
                        )
                        setPadding(14.dp, 10.dp, 14.dp, 10.dp)
                        setOnClickListener {
                            if (isCached) {
                                playRemoteUsbFolder(stick, files, position)
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Dit nummer wordt nog gesynchroniseerd.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    LinearLayout.LayoutParams(
                        58.dp,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )

                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(row)
                    setPadding(0, 0, 0, 9.dp)
                }
            }
        }
        listView.adapter = adapter

        panel.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12.dp, 0, 0)
        }
        val back = TextView(this).apply {
            text = "← 1 STAP TERUG"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
            setBackgroundResource(R.drawable.bg_gold_outline)
            setPadding(14.dp, 13.dp, 14.dp, 13.dp)
            setOnClickListener {
                dialog.dismiss()
                val parent = folder
                    .takeUnless { it == "Hoofdmap" }
                    ?.substringBeforeLast('/', "")
                    .orEmpty()
                showRemoteFolderLevel(stick, parent)
            }
        }
        val player = TextView(this).apply {
            text = "NAAR PLAYER  ›"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#201505"))
            setBackgroundResource(R.drawable.bg_amber_button)
            setPadding(14.dp, 13.dp, 14.dp, 13.dp)
            setOnClickListener { dialog.dismiss() }
        }
        actions.addView(back, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = 8.dp })
        actions.addView(player, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = 8.dp })
        panel.addView(actions)

        val liveHighlight = object : Runnable {
            override fun run() {
                if (dialog.isShowing && !isFinishing && !isDestroyed) {
                    adapter.notifyDataSetChanged()
                    listView.postDelayed(this, 500L)
                }
            }
        }

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            adapter.notifyDataSetChanged()
            listView.postDelayed(liveHighlight, 500L)
        }
        dialog.setOnDismissListener { listView.removeCallbacks(liveHighlight) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.72f }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            (resources.displayMetrics.heightPixels * 0.86f).toInt()
        )
    }

    private fun usbFavoriteKey(file: RemoteUsbMusicClient.RemoteFile): String =
        usbFavoriteKey(file.deviceId, file.stickId, file.path)

    private fun usbFavoriteKey(deviceId: String, stickId: String, path: String): String =
        deviceId + "\n" + stickId + "\n" + path

    private fun toggleUsbFavorite(
        stick: RemoteUsbMusicClient.RemoteStick,
        file: RemoteUsbMusicClient.RemoteFile,
        button: TextView
    ) {
        val key = usbFavoriteKey(file)
        val add = !favoriteUsbKeys.contains(key)
        button.isEnabled = false
        remoteMusicIo.execute {
            val allowed = try {
                CarFamilyAccess.refreshMusicRights(this)
            } catch (_: Exception) {
                false
            }
            val ok = if (allowed) {
                try {
                    if (!RemoteUsbMusicClient.hasToken(this) &&
                        !RemoteUsbMusicClient.loginForBrowsing(this)
                    ) {
                        false
                    } else {
                        RemoteUsbMusicClient.setUsbFavorite(this, stick, file, add)
                        true
                    }
                } catch (_: Exception) {
                    false
                }
            } else false

            runOnUiThread {
                button.isEnabled = true
                if (!allowed) {
                    Toast.makeText(
                        this,
                        "Dit apparaat heeft geen muziekrechten. Geef deze eerst via Main.",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (ok) {
                    if (add) favoriteUsbKeys += key else favoriteUsbKeys -= key
                    button.text = if (add) "★" else "☆"
                    Toast.makeText(
                        this,
                        if (add) "Toegevoegd aan The One Favorites"
                        else "Verwijderd uit The One Favorites",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "Favoriet opslaan mislukt", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestRemoteUsbDownload(file: RemoteUsbMusicClient.RemoteFile) {
        remoteMusicIo.execute {
            val allowed = try {
                CarFamilyAccess.refreshMusicRights(this)
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (!allowed) {
                    Toast.makeText(
                        this,
                        "Dit apparaat heeft geen muziekrechten. Geef deze eerst via Main.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                showRemoteUsbDownloadPin(file)
            }
        }
    }

    private fun showRemoteUsbDownloadPin(file: RemoteUsbMusicClient.RemoteFile) {
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

    private fun isRemoteUsbTrackCurrent(
        file: RemoteUsbMusicClient.RemoteFile
    ): Boolean {
        val state = UsbPlaybackService.snapshot()
        if (!state.hasTrack) return false

        val activeUri = state.uri.orEmpty()
        val isSharedMedia =
            activeUri.contains("the-one-remote-api", ignoreCase = true) ||
                activeUri.contains("music.php", ignoreCase = true)
        if (!isSharedMedia) return false

        return state.title.equals(
            cleanRemoteUsbTrackTitle(file.displayName),
            ignoreCase = true
        )
    }

    private fun playRemoteUsbFolder(
        stick: RemoteUsbMusicClient.RemoteStick,
        files: List<RemoteUsbMusicClient.RemoteFile>,
        index: Int
    ) {
        try {
            val selectedFile = files.getOrNull(index)
                ?: throw IllegalArgumentException("Nummer niet gevonden")
            if (!selectedFile.cached) {
                Toast.makeText(
                    this,
                    "Dit nummer wordt nog gesynchroniseerd.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val playableFiles = files.filter { it.cached }
            val playableIndex = playableFiles.indexOfFirst {
                it.deviceId == selectedFile.deviceId &&
                    it.stickId == selectedFile.stickId &&
                    it.path.equals(selectedFile.path, ignoreCase = true)
            }.coerceAtLeast(0)

            val queue = playableFiles.map {
                UsbPlaybackService.QueueItem(
                    RemoteUsbMusicClient.streamUrl(this, it),
                    cleanRemoteUsbTrackTitle(it.displayName)
                )
            }
            currentFamilyStick = stick
            currentFamilyFiles = playableFiles.toList()
            UsbPlaybackService.play(this, queue, playableIndex)
            Toast.makeText(this, "Shared Media speelt af", Toast.LENGTH_SHORT).show()
        } catch (_: RemoteUsbMusicClient.AuthRequired) {
            RemoteUsbMusicClient.clearToken(this)
            remoteMusicIo.execute {
                val ok = try { RemoteUsbMusicClient.loginForBrowsing(this) } catch (_: Exception) { false }
                runOnUiThread {
                    if (ok) {
                        playRemoteUsbFolder(stick, files, index)
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
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (CarMediaKeyHandler.handle(this, event)) {
            refreshCarAudioPlayer()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        ensureCarPersonRegistration()
        statusText.text = MessageBus.currentStatus()
        buildTiles()
        refreshCarVolume()
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
