package com.gmailorg.hub

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MoviesActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var searchButton: View
    private lateinit var resultContainer: LinearLayout
    private lateinit var upcomingContainer: LinearLayout
    private lateinit var musicResultContainer: LinearLayout
    private lateinit var musicPlayerCard: View
    private lateinit var musicNowPlaying: TextView
    private lateinit var musicPlaybackState: TextView
    private lateinit var musicSeekBar: SeekBar
    private lateinit var musicTimeText: TextView
    private lateinit var musicWebPlayer: WebView
    private var youtubeActive = false
    private var youtubePlaying = false
    private var musicSeekDragging = false
    private var musicSeekDurationMs = 0
    private var pendingMusicTitle: String? = null
    private var pendingMusicStartedAt = 0L
    private var currentRemoteUsbStick: RemoteUsbMusicClient.RemoteStick? = null
    private var currentRemoteUsbFolder: String = ""
    private var currentRemoteUsbFiles: List<RemoteUsbMusicClient.RemoteFile> = emptyList()
    private val remoteMusicIo = Executors.newSingleThreadExecutor()
    private val compactPlayerRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && ::musicNowPlaying.isInitialized) {
                refreshCompactPlayer()
                musicNowPlaying.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)
        MenuButtonHelper.attach(this)

        titleInput = findViewById(R.id.movieTitleInput)
        searchButton = findViewById(R.id.movieSearchButton)
        resultContainer = findViewById(R.id.movieResultContainer)
        upcomingContainer = findViewById(R.id.upcomingContainer)

        findViewById<View>(R.id.backButton).setOnClickListener { MenuButtonHelper.goToMenu(this) }
        searchButton.setOnClickListener { searchAll() }
        findViewById<View>(R.id.upcomingLoadButton).setOnClickListener { loadUpcoming() }
        titleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchAll()
                true
            } else {
                false
            }
        }

        musicResultContainer = findViewById(R.id.musicResultContainer)
        musicPlayerCard = findViewById(R.id.musicPlayerCard)
        musicNowPlaying = findViewById(R.id.musicNowPlaying)
        musicPlaybackState = findViewById(R.id.musicPlaybackState)
        musicSeekBar = findViewById(R.id.musicSeekBar)
        musicTimeText = findViewById(R.id.musicTimeText)
        musicWebPlayer = findViewById(R.id.musicWebPlayer)
        configureMusicPlayer()
        configureMusicSeekBar()
        musicNowPlaying.setOnClickListener {
            openCurrentSharedMediaTrackFolder()
        }
        musicPlayerCard.setOnClickListener {
            openCurrentSharedMediaTrackFolder()
        }
        findViewById<View>(R.id.musicPreviousButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_PREVIOUS)
            } else {
                musicWebPlayer.evaluateJavascript("window.theOnePrevious && window.theOnePrevious();", null)
            }
        }
        findViewById<View>(R.id.musicPlayPauseButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_TOGGLE)
            } else if (youtubeActive) {
                musicWebPlayer.evaluateJavascript("window.theOneToggle && window.theOneToggle();", null)
                youtubePlaying = !youtubePlaying
                musicPlaybackState.text = if (youtubePlaying) "Speelt af" else "Gepauzeerd"
            }
        }
        findViewById<View>(R.id.musicStopButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_STOP)
            } else if (youtubeActive) {
                musicWebPlayer.evaluateJavascript("window.theOneStop && window.theOneStop();", null)
            }
            youtubeActive = false
            youtubePlaying = false
            pendingMusicTitle = null
            pendingMusicStartedAt = 0L
            currentRemoteUsbStick = null
            currentRemoteUsbFiles = emptyList()
            currentRemoteUsbFolder = ""
            musicNowPlaying.paintFlags = musicNowPlaying.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            musicNowPlaying.text = "Geen muziek actief"
            musicPlaybackState.text = "Gestopt"
            resetMusicSeekUi()
        }
        findViewById<View>(R.id.musicNextButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_NEXT)
            } else {
                musicWebPlayer.evaluateJavascript("window.theOneNext && window.theOneNext();", null)
            }
        }

        findViewById<View>(R.id.supremacyMixesButton).setOnClickListener {
            startActivity(Intent(this, SupremacyMixesActivity::class.java))
        }

        findViewById<View>(R.id.remoteUsbMusicButton).setOnClickListener {
            openRemoteUsbMusic()
        }

    }

    override fun onResume() {
        super.onResume()
        SupremacyPlaybackService.resumeLastSessionIfNeeded(this)
        musicNowPlaying.removeCallbacks(compactPlayerRefresh)
        musicNowPlaying.postDelayed(compactPlayerRefresh, 250)
    }

    override fun onPause() {
        if (::musicNowPlaying.isInitialized) {
            musicNowPlaying.removeCallbacks(compactPlayerRefresh)
        }
        super.onPause()
    }

    private fun openCurrentSharedMediaTrackFolder() {
        if (!SupremacyPlaybackService.isActive(this)) return

        val source = SupremacyPlaybackService.currentSource(this)
        if (!source.startsWith("Shared Media •", ignoreCase = true)) return

        val activeTitle =
            cleanUsbTrackTitle(SupremacyPlaybackService.currentTitle(this))
        val activeIndex = SupremacyPlaybackService.currentQueueIndex(this)

        val currentStick = currentRemoteUsbStick
        if (currentStick != null) {
            val active = currentRemoteUsbFiles.getOrNull(activeIndex)
                ?: currentStick.files.firstOrNull {
                    cleanUsbTrackTitle(it.displayName)
                        .equals(activeTitle, ignoreCase = true)
                }
            if (active != null) {
                currentRemoteUsbFolder = normalizeRemoteFolder(active.folder)
                showRemoteFolderLevel(
                    currentStick,
                    currentRemoteUsbFolder,
                    openCurrentFolder = true
                )
                return
            }
        }

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

            val parts = source.split(" • ")
            val deviceName = parts.getOrNull(1).orEmpty()
            val stickName = parts.drop(2).joinToString(" • ")

            val stick = sticks.firstOrNull {
                it.deviceName.equals(deviceName, ignoreCase = true) &&
                    it.stickName.equals(stickName, ignoreCase = true)
            } ?: sticks.firstOrNull {
                it.deviceName.equals(deviceName, ignoreCase = true)
            }

            val active = stick?.files?.firstOrNull {
                cleanUsbTrackTitle(it.displayName)
                    .equals(activeTitle, ignoreCase = true)
            }

            runOnUiThread {
                if (stick != null && active != null) {
                    currentRemoteUsbStick = stick
                    currentRemoteUsbFiles = stick.files
                    currentRemoteUsbFolder =
                        normalizeRemoteFolder(active.folder)
                    showRemoteFolderLevel(
                        stick,
                        currentRemoteUsbFolder,
                        openCurrentFolder = true
                    )
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

    private fun refreshCompactPlayer() {
        if (SupremacyPlaybackService.isActive(this)) {
            pendingMusicTitle = null
            pendingMusicStartedAt = 0L
            musicNowPlaying.text =
                cleanUsbTrackTitle(SupremacyPlaybackService.currentTitle(this))
            val isSharedMedia = SupremacyPlaybackService.currentSource(this)
                .startsWith("Shared Media •", ignoreCase = true)
            if (isSharedMedia && currentRemoteUsbStick != null) {
                musicNowPlaying.paintFlags =
                    musicNowPlaying.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                musicNowPlaying.contentDescription =
                    "Tik om naar de map van het spelende nummer te gaan"
            } else {
                musicNowPlaying.paintFlags =
                    musicNowPlaying.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            }
            musicPlaybackState.text =
                if (SupremacyPlaybackService.isPlaying(this)) "Speelt af" else "Laden…"

            val duration = SupremacyPlaybackService.currentDurationMs()
            val position = SupremacyPlaybackService.currentPositionMs(this)
            updateMusicSeekUi(position, duration)
            return
        }

        if (youtubeActive) {
            pendingMusicTitle = null
            pendingMusicStartedAt = 0L
            musicPlaybackState.text = if (youtubePlaying) "Speelt af" else "Gepauzeerd"
            refreshYoutubeSeekUi()
            return
        }

        val pendingTitle = pendingMusicTitle
        if (
            !pendingTitle.isNullOrBlank() &&
            System.currentTimeMillis() - pendingMusicStartedAt < 12_000L
        ) {
            musicNowPlaying.text = pendingTitle
            musicPlaybackState.text = "Laden…"
            return
        }

        pendingMusicTitle = null
        pendingMusicStartedAt = 0L
        musicNowPlaying.text = "Geen muziek actief"
        musicPlaybackState.text = "Gestopt"
        resetMusicSeekUi()
    }

    private fun configureMusicSeekBar() {
        musicSeekBar.max = 1000
        musicSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser || musicSeekDurationMs <= 0) return
                    val preview =
                        (musicSeekDurationMs.toLong() * progress / 1000L)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                    musicTimeText.text =
                        formatMusicTime(preview) + " / " +
                            formatMusicTime(musicSeekDurationMs)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    musicSeekDragging = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    musicSeekDragging = false
                    if (musicSeekDurationMs <= 0) return

                    val target =
                        (musicSeekDurationMs.toLong() *
                            musicSeekBar.progress / 1000L)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()

                    if (SupremacyPlaybackService.isActive(this@MoviesActivity)) {
                        SupremacyPlaybackService.seek(
                            this@MoviesActivity,
                            target
                        )
                    } else if (youtubeActive) {
                        val seconds = target / 1000.0
                        musicWebPlayer.evaluateJavascript(
                            "window.theOneSeek && window.theOneSeek($seconds);",
                            null
                        )
                    }
                    updateMusicSeekUi(target, musicSeekDurationMs)
                }
            }
        )
    }

    private fun updateMusicSeekUi(positionMs: Int, durationMs: Int) {
        val safeDuration = durationMs.coerceAtLeast(0)
        val safePosition = positionMs.coerceAtLeast(0)
            .coerceAtMost(
                if (safeDuration > 0) safeDuration else Int.MAX_VALUE
            )

        musicSeekDurationMs = safeDuration
        musicSeekBar.isEnabled = safeDuration > 0

        if (!musicSeekDragging) {
            musicSeekBar.progress =
                if (safeDuration > 0) {
                    ((safePosition.toLong() * 1000L) / safeDuration)
                        .coerceIn(0L, 1000L)
                        .toInt()
                } else {
                    0
                }

            musicTimeText.text =
                formatMusicTime(safePosition) + " / " +
                    if (safeDuration > 0) {
                        formatMusicTime(safeDuration)
                    } else {
                        "00:00"
                    }
        }
    }

    private fun refreshYoutubeSeekUi() {
        if (!youtubeActive || musicSeekDragging) return

        musicWebPlayer.evaluateJavascript(
            "(window.theOnePosition ? window.theOnePosition() : 0).toString()"
        ) { positionRaw ->
            val positionSeconds = positionRaw
                ?.trim()
                ?.trim('"')
                ?.toDoubleOrNull()
                ?: 0.0

            musicWebPlayer.evaluateJavascript(
                "(window.theOneDuration ? window.theOneDuration() : 0).toString()"
            ) { durationRaw ->
                val durationSeconds = durationRaw
                    ?.trim()
                    ?.trim('"')
                    ?.toDoubleOrNull()
                    ?: 0.0

                val positionMs =
                    (positionSeconds * 1000.0)
                        .coerceAtLeast(0.0)
                        .toInt()
                val durationMs =
                    (durationSeconds * 1000.0)
                        .coerceAtLeast(0.0)
                        .toInt()

                updateMusicSeekUi(positionMs, durationMs)
            }
        }
    }

    private fun resetMusicSeekUi() {
        musicSeekDragging = false
        musicSeekDurationMs = 0
        if (::musicSeekBar.isInitialized) {
            musicSeekBar.progress = 0
            musicSeekBar.isEnabled = false
        }
        if (::musicTimeText.isInitialized) {
            musicTimeText.text = "00:00 / 00:00"
        }
    }

    private fun formatMusicTime(milliseconds: Int): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(
                java.util.Locale.ROOT,
                "%d:%02d:%02d",
                hours,
                minutes,
                seconds
            )
        } else {
            String.format(
                java.util.Locale.ROOT,
                "%02d:%02d",
                minutes,
                seconds
            )
        }
    }

    private fun sendSupremacyAction(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, SupremacyPlaybackService::class.java).apply {
                this.action = action
            }
        )
        // De service verwerkt acties asynchroon. Een paar korte refreshes zorgen
        // dat titel, speelstatus en seekbar meteen volgen zonder opnieuw openen.
        refreshCompactPlayer()
        musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 100)
        musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 300)
        musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 700)
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(Color.parseColor("#D8A451"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.WHITE)
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
        val labels = sticks.map {
            val cached = it.files.size
            val total = it.totalFiles
            val status = if (cached >= total && total > 0) {
                "$cached nummer" + if (cached == 1) "" else "s"
            } else {
                "$cached van $total beschikbaar"
            }
            it.deviceName + " • " + it.stickName + " • " + status
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Shared Media")
            .setItems(labels) { _, which ->
                val stick = sticks[which]
                if (stick.files.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(stick.deviceName + " • " + stick.stickName)
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
            .setPositiveButton("Vernieuwen", null)
            .setNegativeButton("Sluiten", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(Color.parseColor("#D8A451"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.parseColor("#D8A451"))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                loadRemoteUsbCatalog()
            }
        }
        dialog.show()
    }

    private fun normalizeRemoteFolder(value: String): String =
        value.replace('\\', '/').trim('/').let { if (it.equals("Hoofdmap", true)) "" else it }

    private fun parentRemoteFolder(value: String): String {
        val folder = normalizeRemoteFolder(value)
        return folder.substringBeforeLast('/', "")
    }

    private fun showRemoteFolderDialog(stick: RemoteUsbMusicClient.RemoteStick) {
        showRemoteFolderLevel(stick, "")
    }

    private fun showRemoteFolderLevel(
        stick: RemoteUsbMusicClient.RemoteStick,
        folder: String,
        openCurrentFolder: Boolean = false
    ) {
        val normalized = normalizeRemoteFolder(folder)
        val directFiles = stick.files
            .filter { normalizeRemoteFolder(it.folder) == normalized }
            .sortedBy { it.name.lowercase() }

        if (openCurrentFolder && directFiles.isNotEmpty()) {
            showRemoteTrackDialog(stick, normalized, directFiles)
            return
        }

        val prefix = if (normalized.isBlank()) "" else "$normalized/"
        val childFolders = stick.files
            .map { normalizeRemoteFolder(it.folder) }
            .filter { it.length > normalized.length && it.startsWith(prefix, ignoreCase = true) }
            .mapNotNull { path ->
                val remainder = path.removePrefix(prefix)
                val child = remainder.substringBefore('/').trim()
                if (child.isBlank()) null else if (normalized.isBlank()) child else "$normalized/$child"
            }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        if (normalized.isNotBlank() && childFolders.isEmpty() && directFiles.isNotEmpty()) {
            showRemoteTrackDialog(stick, normalized, directFiles)
            return
        }

        val labels = buildList {
            childFolders.forEach { add("📁 " + it.substringAfterLast('/')) }
            directFiles.forEach {
                add(
                    (if (isRemoteUsbTrackCurrent(stick, it)) "▶ NU • " else "🎵 ") +
                        cleanUsbTrackTitle(it.displayName)
                )
            }
        }

        if (labels.isEmpty()) {
            val dialog = AlertDialog.Builder(this)
                .setTitle(if (normalized.isBlank()) stick.deviceName + " • " + stick.stickName else normalized)
                .setMessage("Deze map bevat geen beschikbare nummers.")
                .setNegativeButton(
                    if (normalized.isBlank()) "Sluiten" else "← 1 stap terug"
                ) { _, _ ->
                    if (normalized.isNotBlank()) {
                        showRemoteFolderLevel(stick, parentRemoteFolder(normalized))
                    }
                }
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(Color.parseColor("#D8A451"))
            }
            dialog.show()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (normalized.isBlank())
                    stick.deviceName + " • " + stick.stickName
                else
                    normalized
            )
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < childFolders.size) {
                    showRemoteFolderLevel(stick, childFolders[which])
                } else {
                    val index = which - childFolders.size
                    playRemoteUsbFolder(stick, directFiles, index, normalized)
                }
            }
            .setNegativeButton(
                if (normalized.isBlank()) "Sluiten" else "← 1 stap terug"
            ) { _, _ ->
                if (normalized.isNotBlank()) {
                    showRemoteFolderLevel(stick, parentRemoteFolder(normalized))
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.parseColor("#D8A451"))
        }
        dialog.show()
    }

    private fun showRemoteTrackDialog(
        stick: RemoteUsbMusicClient.RemoteStick,
        folder: String,
        files: List<RemoteUsbMusicClient.RemoteFile>
    ) {
        val normalized = normalizeRemoteFolder(folder)
        val trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 8, 18, 8)
        }

        val density = resources.displayMetrics.density
        val downloadWidth = (58 * density).toInt()
        val playWidth = (62 * density).toInt()
        val actionGap = (14 * density).toInt()
        val trackRows = mutableListOf<LinearLayout>()
        val trackNumbers = mutableListOf<TextView>()
        val trackTitles = mutableListOf<TextView>()
        val trackPlayButtons = mutableListOf<TextView>()

        files.forEachIndexed { index, file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    (8 * density).toInt(),
                    (6 * density).toInt(),
                    (8 * density).toInt(),
                    (6 * density).toInt()
                )
            }

            val number = TextView(this).apply {
                text = (index + 1).toString().padStart(2, '0')
                textSize = 12f
                setTextColor(Color.parseColor("#20B8FF"))
                gravity = android.view.Gravity.CENTER
            }
            row.addView(
                number,
                LinearLayout.LayoutParams((42 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            )

            val title = TextView(this).apply {
                text = cleanUsbTrackTitle(file.displayName)
                textSize = 16f
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(
                    (8 * density).toInt(),
                    (14 * density).toInt(),
                    (10 * density).toInt(),
                    (14 * density).toInt()
                )
                setOnClickListener {
                    playRemoteUsbFolder(stick, files, index, normalized)
                }
            }
            row.addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            val download = TextView(this).apply {
                text = "↓"
                textSize = 21f
                setTextColor(Color.parseColor("#D8A451"))
                gravity = android.view.Gravity.CENTER
                contentDescription = "Download ${file.name}"
                setPadding(
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                    (12 * density).toInt()
                )
                setOnClickListener { requestRemoteUsbDownload(file) }
            }
            row.addView(
                download,
                LinearLayout.LayoutParams(downloadWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            )

            row.addView(
                View(this),
                LinearLayout.LayoutParams(actionGap, 1)
            )

            val play = TextView(this).apply {
                text = "▶"
                textSize = 19f
                setTextColor(Color.parseColor("#20B8FF"))
                gravity = android.view.Gravity.CENTER
                contentDescription = "Speel ${file.name} af"
                setPadding(
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                    (12 * density).toInt(),
                    (12 * density).toInt()
                )
                setOnClickListener {
                    playRemoteUsbFolder(stick, files, index, normalized)
                }
            }
            row.addView(
                play,
                LinearLayout.LayoutParams(playWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            )

            trackRows.add(row)
            trackNumbers.add(number)
            trackTitles.add(title)
            trackPlayButtons.add(play)

            trackList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            if (index < files.lastIndex) {
                trackList.addView(
                    View(this).apply { setBackgroundColor(Color.parseColor("#263241")) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                )
            }
        }

        fun refreshPlayingTrackHighlight() {
            files.forEachIndexed { index, file ->
                val isCurrent = isRemoteUsbTrackCurrent(stick, file)
                trackRows[index].setBackgroundColor(
                    if (isCurrent) Color.parseColor("#123247") else Color.TRANSPARENT
                )
                trackNumbers[index].text =
                    if (isCurrent) "♪" else (index + 1).toString().padStart(2, '0')
                trackNumbers[index].setTextColor(
                    Color.parseColor(if (isCurrent) "#D8A451" else "#20B8FF")
                )
                trackTitles[index].text =
                    (if (isCurrent) "▶ NU • " else "") +
                        cleanUsbTrackTitle(file.displayName)
                trackTitles[index].setTextColor(
                    Color.parseColor(if (isCurrent) "#D8A451" else "#FFFFFF")
                )
                trackTitles[index].setTypeface(
                    null,
                    if (isCurrent) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL
                )
                trackPlayButtons[index].setTextColor(
                    Color.parseColor(if (isCurrent) "#D8A451" else "#20B8FF")
                )
                trackRows[index].contentDescription =
                    if (isCurrent) "Nu actief: ${cleanUsbTrackTitle(file.displayName)}"
                    else cleanUsbTrackTitle(file.displayName)
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(trackList)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (normalized.isBlank()) "Hoofdmap" else normalized)
            .setView(scroll)
            .setNegativeButton("← 1 stap terug") { _, _ ->
                showRemoteFolderLevel(stick, parentRemoteFolder(normalized))
            }
            .create()

        val liveHighlight = object : Runnable {
            override fun run() {
                if (dialog.isShowing && !isFinishing && !isDestroyed) {
                    refreshPlayingTrackHighlight()
                    trackList.postDelayed(this, 500L)
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.parseColor("#D8A451"))
            refreshPlayingTrackHighlight()
            trackList.postDelayed(liveHighlight, 500L)
        }
        dialog.setOnDismissListener {
            trackList.removeCallbacks(liveHighlight)
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
                .setDescription("The One • Shared Media")
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

    private fun cleanUsbTrackTitle(raw: String): String =
        raw
            .replace(Regex("\\.(mp3|wma|m4a|aac|flac|ogg|oga|opus|wav|mp4)$", RegexOption.IGNORE_CASE), "")
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isRemoteUsbTrackCurrent(
        stick: RemoteUsbMusicClient.RemoteStick,
        file: RemoteUsbMusicClient.RemoteFile
    ): Boolean {
        if (!SupremacyPlaybackService.isActive(this)) return false

        val source = SupremacyPlaybackService.currentSource(this)
        val exactSource =
            "Shared Media • " + stick.deviceName + " • " + stick.stickName
        val legacySource = "Shared Media • " + stick.deviceName
        if (!source.equals(exactSource, ignoreCase = true) &&
            !source.equals(legacySource, ignoreCase = true)
        ) {
            return false
        }

        return cleanUsbTrackTitle(SupremacyPlaybackService.currentTitle(this))
            .equals(cleanUsbTrackTitle(file.displayName), ignoreCase = true)
    }

    private fun playRemoteUsbFolder(
        stick: RemoteUsbMusicClient.RemoteStick,
        files: List<RemoteUsbMusicClient.RemoteFile>,
        index: Int,
        folder: String = files.getOrNull(index)?.folder.orEmpty()
    ) {
        try {
            musicWebPlayer.evaluateJavascript("window.theOneStop && window.theOneStop();", null)
            youtubeActive = false
            youtubePlaying = false

            val urls = ArrayList(files.map { RemoteUsbMusicClient.streamUrl(this, it) })
            val titles = ArrayList(files.map { cleanUsbTrackTitle(it.displayName) })

            ContextCompat.startForegroundService(
                this,
                Intent(this, SupremacyPlaybackService::class.java).apply {
                    action = SupremacyPlaybackService.ACTION_PLAY
                    putStringArrayListExtra(SupremacyPlaybackService.EXTRA_QUEUE_URLS, urls)
                    putStringArrayListExtra(SupremacyPlaybackService.EXTRA_QUEUE_TITLES, titles)
                    putExtra(SupremacyPlaybackService.EXTRA_INDEX, index)
                    putExtra(
                        SupremacyPlaybackService.EXTRA_SOURCE,
                        "Shared Media • " + stick.deviceName + " • " + stick.stickName
                    )
                }
            )

            currentRemoteUsbStick = stick
            currentRemoteUsbFiles = files.toList()
            currentRemoteUsbFolder = normalizeRemoteFolder(folder)
            pendingMusicTitle = cleanUsbTrackTitle(files[index].displayName)
            pendingMusicStartedAt = System.currentTimeMillis()
            musicNowPlaying.text = pendingMusicTitle
            musicNowPlaying.paintFlags =
                musicNowPlaying.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            musicNowPlaying.contentDescription =
                "Tik om de Shared Media-map van dit nummer te openen"
            musicPlaybackState.text = "Laden…"
            resetMusicSeekUi()

            // Blijf snel verversen, maar laat refreshCompactPlayer de gekozen
            // titel vasthouden totdat de audioservice hem echt heeft overgenomen.
            refreshCompactPlayer()
            musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 100)
            musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 300)
            musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 700)
            musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 1500)
        } catch (_: RemoteUsbMusicClient.AuthRequired) {
            RemoteUsbMusicClient.clearToken(this)
            remoteMusicIo.execute {
                val ok = try { RemoteUsbMusicClient.loginForBrowsing(this) } catch (_: Exception) { false }
                runOnUiThread {
                    if (ok) {
                        playRemoteUsbFolder(stick, files, index, folder)
                    } else {
                        Toast.makeText(this, "Shared Media kon niet opnieuw verbinden.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Afspelen mislukt", Toast.LENGTH_LONG).show()
        }
    }

    private fun searchAll() {
        val query = titleInput.text.toString().trim()
        if (query.isBlank()) return

        // Eén zoekopdracht voor films/series én muziek.
        resultContainer.removeAllViews()
        musicResultContainer.removeAllViews()
        resultContainer.visibility = View.VISIBLE
        musicResultContainer.visibility = View.VISIBLE

        addResultLine("Films & series", bold = true)
        addResultLine("Zoeken naar “$query”…", dim = true)
        addMusicLine("Muziek", dim = false)
        addMusicLine("Zoeken naar “$query”…", dim = true)

        MovieLookup.search(query) { outcome ->
            resultContainer.removeAllViews()
            addResultLine("Films & series", bold = true)
            when (outcome) {
                is MovieLookup.LookupOutcome.Success -> showMovieResult(outcome.result)
                is MovieLookup.LookupOutcome.NotFound ->
                    addResultLine("Geen film of serie gevonden voor “${outcome.query}”.", dim = true)
                is MovieLookup.LookupOutcome.Error ->
                    addResultLine(outcome.message, dim = true)
            }
        }

        MusicLookup.search(query) { outcome ->
            musicResultContainer.removeAllViews()
            addMusicLine("Muziek")
            when (outcome) {
                is MusicLookup.LookupOutcome.Success -> showMusicResults(outcome.results)
                is MusicLookup.LookupOutcome.NotFound ->
                    addMusicLine("Geen muziek gevonden voor “${outcome.query}”.", dim = true)
                is MusicLookup.LookupOutcome.Error ->
                    addMusicLine(outcome.message, dim = true)
            }
        }
    }

    private fun searchMusic(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        musicResultContainer.removeAllViews()
        musicResultContainer.visibility = View.VISIBLE
        addMusicLine("Zoeken naar “$trimmed”…", dim = true)

        MusicLookup.search(trimmed) { outcome ->
            musicResultContainer.removeAllViews()
            when (outcome) {
                is MusicLookup.LookupOutcome.Success -> showMusicResults(outcome.results)
                is MusicLookup.LookupOutcome.NotFound ->
                    addMusicLine("Niets gevonden voor “${outcome.query}”.", dim = true)
                is MusicLookup.LookupOutcome.Error ->
                    addMusicLine(outcome.message, dim = true)
            }
        }
    }

    private fun showMusicResults(results: List<MusicLookup.MusicResult>) {
        results.forEachIndexed { index, result ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                setOnClickListener { playVideo(result, results.drop(index).map { it.videoId }) }
            }
            val titleView = TextView(this).apply {
                text = result.title
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 14f
                maxLines = 2
            }
            row.addView(titleView)
            if (result.channel.isNotBlank()) {
                val channelView = TextView(this).apply {
                    text = result.channel
                    setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                    textSize = 12f
                    setPadding(0, 2, 0, 0)
                }
                row.addView(channelView)
            }
            musicResultContainer.addView(row)
        }
    }

    private fun configureMusicPlayer() {
        musicWebPlayer.settings.javaScriptEnabled = true
        musicWebPlayer.settings.domStorageEnabled = true
        musicWebPlayer.settings.mediaPlaybackRequiresUserGesture = false
        musicWebPlayer.webViewClient = WebViewClient()
        musicWebPlayer.webChromeClient = WebChromeClient()
    }

    private fun playVideo(result: MusicLookup.MusicResult, queue: List<String>) {
        if (SupremacyPlaybackService.isActive(this)) {
            sendSupremacyAction(SupremacyPlaybackService.ACTION_STOP)
        }
        youtubeActive = true
        youtubePlaying = true
        musicNowPlaying.text = result.title +
            if (result.channel.isNotBlank()) "  •  ${result.channel}" else ""
        musicPlaybackState.text = "Speelt af"
        resetMusicSeekUi()

        val cleanQueue = queue
            .map { id -> id.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
            .filter { it.isNotBlank() }
            .take(20)

        val queueJson = org.json.JSONArray(cleanQueue).toString()
        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                html,body,#player{width:100%;height:100%;margin:0;background:#05070B;overflow:hidden}
              </style>
            </head>
            <body>
              <div id="player"></div>
              <script>
                const queue = $queueJson;
                let player;
                function onYouTubeIframeAPIReady() {
                  if (!queue.length) return;
                  player = new YT.Player('player', {
                    width: '100%',
                    height: '100%',
                    playerVars: { autoplay: 1, playsinline: 1, rel: 0, modestbranding: 1 },
                    events: {
                      onReady: e => e.target.loadPlaylist({
                        playlist: queue,
                        index: 0,
                        startSeconds: 0
                      })
                    }
                  });
                  window.theOneToggle = () => {
                    if (!player || !player.getPlayerState) return;
                    const state = player.getPlayerState();
                    if (state === YT.PlayerState.PLAYING) player.pauseVideo();
                    else player.playVideo();
                  };
                  window.theOnePrevious = () => {
                    if (player && player.previousVideo) player.previousVideo();
                  };
                  window.theOneNext = () => {
                    if (player && player.nextVideo) player.nextVideo();
                  };
                  window.theOneStop = () => {
                    if (player && player.stopVideo) player.stopVideo();
                  };
                  window.theOneSeek = seconds => {
                    if (player && player.seekTo) player.seekTo(seconds, true);
                  };
                  window.theOnePosition = () => {
                    if (!player || !player.getCurrentTime) return 0;
                    return player.getCurrentTime() || 0;
                  };
                  window.theOneDuration = () => {
                    if (!player || !player.getDuration) return 0;
                    return player.getDuration() || 0;
                  };
                }
                const api = document.createElement('script');
                api.src = 'https://www.youtube.com/iframe_api';
                document.head.appendChild(api);
              </script>
            </body>
            </html>
        """.trimIndent()

        musicWebPlayer.loadDataWithBaseURL(
            "https://www.youtube.com",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }
    @Deprecated("Back keeps the player alive and returns to The One menu")
    override fun onBackPressed() {
        MenuButtonHelper.goToMenu(this)
    }

    override fun onDestroy() {
        if (::musicNowPlaying.isInitialized) {
            musicNowPlaying.removeCallbacks(compactPlayerRefresh)
        }
        remoteMusicIo.shutdownNow()
        if (isFinishing && ::musicWebPlayer.isInitialized) {
            musicWebPlayer.stopLoading()
            musicWebPlayer.destroy()
        }
        super.onDestroy()
    }

    private fun addMusicLine(text: String, dim: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, if (dim) R.color.text_dim else R.color.text_main))
            textSize = 13f
            setPadding(0, 4, 0, 4)
        }
        musicResultContainer.addView(view)
    }

    private fun loadUpcoming() {
        upcomingContainer.removeAllViews()
        addUpcomingLine("Bezig met laden...", dim = true)

        MovieLookup.fetchUpcomingMarvelAndDc { releases, error ->
            upcomingContainer.removeAllViews()
            if (error != null) {
                addUpcomingLine(error, dim = true)
                return@fetchUpcomingMarvelAndDc
            }
            if (releases.isEmpty()) {
                addUpcomingLine("Geen aankomende releases gevonden.", dim = true)
                return@fetchUpcomingMarvelAndDc
            }
            releases.forEach { release ->
                val dateText = release.releaseDate?.let { formatDutchDate(it) } ?: "datum onbekend"
                addUpcomingLine("${release.title} — ${release.studio}", bold = true)
                addUpcomingLine(dateText, dim = true)
            }
        }
    }

    private fun formatDutchDate(isoDate: String): String {
        return try {
            val parts = isoDate.split("-")
            val months = listOf(
                "januari", "februari", "maart", "april", "mei", "juni",
                "juli", "augustus", "september", "oktober", "november", "december"
            )
            val day = parts[2].toInt()
            val month = months[parts[1].toInt() - 1]
            val year = parts[0]
            "$day $month $year"
        } catch (e: Exception) {
            isoDate
        }
    }

    private fun addUpcomingLine(text: String, bold: Boolean = false, dim: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, if (dim) R.color.text_dim else R.color.text_main))
            textSize = if (bold) 15f else 13f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, if (dim) 10 else 2)
        }
        upcomingContainer.addView(view)
    }

    private fun searchMovie() {
        val query = titleInput.text.toString().trim()
        if (query.isBlank()) return

        resultContainer.removeAllViews()
        resultContainer.visibility = View.VISIBLE
        addResultLine("Zoeken naar “$query”…", dim = true)

        MovieLookup.search(query) { outcome ->
            resultContainer.removeAllViews()
            when (outcome) {
                is MovieLookup.LookupOutcome.Success -> showMovieResult(outcome.result)
                is MovieLookup.LookupOutcome.NotFound ->
                    addResultLine("Geen film of serie gevonden voor “${outcome.query}”.", dim = true)
                is MovieLookup.LookupOutcome.Error ->
                    addResultLine(outcome.message, dim = true)
            }
        }
    }

    private fun showMovieResult(result: MovieLookup.MovieResult) {
        val type = if (result.isSeries) "Serie" else "Film"
        addResultLine(type, dim = true)
        val titleLine = if (result.year != null) "${result.title} (${result.year})" else result.title
        addResultLine(titleLine, bold = true)

        if (result.availableOn.isEmpty() && result.rentOrBuyOn.isEmpty()) {
            addResultLine("Niet gevonden bij een streamingdienst in Nederland.", dim = true)
            return
        }
        if (result.availableOn.isNotEmpty()) {
            addResultLine("Kijken (abonnement/gratis): ${result.availableOn.joinToString(", ")}")
        }
        if (result.rentOrBuyOn.isNotEmpty()) {
            addResultLine("Huren of kopen: ${result.rentOrBuyOn.joinToString(", ")}")
        }
    }

    private fun addResultLine(text: String, bold: Boolean = false, dim: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, if (dim) R.color.text_dim else R.color.text_main))
            textSize = if (bold) 17f else 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }
        resultContainer.addView(view)
    }
}
