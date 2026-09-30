package com.gmailorg.hub

import android.app.Dialog
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
import java.util.UUID
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
    private lateinit var musicBroadcastButton: View
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
    private val playerBroadcastIo = Executors.newSingleThreadExecutor()
    private val favoriteUsbKeys = linkedSetOf<String>()
    private var playerBroadcastSessionId = ""
    private var lastPlayerBroadcastId = ""
    @Volatile private var playerBroadcastPollBusy = false
    private val playerBroadcastPoll = object : Runnable {
        override fun run() {
            if (
                isFinishing ||
                isDestroyed ||
                !::musicNowPlaying.isInitialized ||
                playerBroadcastSessionId.isBlank()
            ) return

            val sessionId = playerBroadcastSessionId
            if (!playerBroadcastPollBusy) {
                playerBroadcastPollBusy = true
                playerBroadcastIo.execute {
                    val event = runCatching {
                        RemoteUsbMusicClient.pollPlayerBroadcast(
                            this@MoviesActivity,
                            sessionId
                        )
                    }.getOrNull()

                    runOnUiThread {
                        playerBroadcastPollBusy = false
                        if (
                            playerBroadcastSessionId == sessionId &&
                            event != null &&
                            event.id != lastPlayerBroadcastId
                        ) {
                            lastPlayerBroadcastId = event.id
                            val adjustedPosition =
                                (event.positionMs.toLong() + event.ageMs + 250L)
                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                    .toInt()

                            SupremacyPlaybackService.playBroadcast(
                                this@MoviesActivity,
                                event.url,
                                event.title,
                                event.source,
                                adjustedPosition
                            )
                            Toast.makeText(
                                this@MoviesActivity,
                                "📡 Broadcast • ${event.title}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

            musicNowPlaying.postDelayed(this, 1000L)
        }
    }

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
        musicBroadcastButton = findViewById(R.id.musicBroadcastButton)
        musicBroadcastButton.visibility =
            if (MainDeviceRegistry.isLocallyOwner(this)) View.VISIBLE else View.GONE
        musicBroadcastButton.setOnClickListener {
            broadcastCurrentTrack()
        }
        configureMusicPlayer()
        configureMusicSeekBar()
        musicNowPlaying.setOnClickListener {
            openCurrentMusicSource()
        }
        musicPlayerCard.setOnClickListener {
            openCurrentMusicSource()
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

        findViewById<View>(R.id.favoritesButton).setOnClickListener {
            withSectionAccess(
                MainDeviceRegistry.ACCESS_FAVORITES,
                "The One Favorites"
            ) {
                openTheOneFavorites()
            }
        }

        findViewById<View>(R.id.supremacyMixesButton).setOnClickListener {
            withSectionAccess(
                MainDeviceRegistry.ACCESS_MIXES,
                "The One Mixes"
            ) {
                startActivity(Intent(this, SupremacyMixesActivity::class.java))
            }
        }

        findViewById<View>(R.id.remoteUsbMusicButton).setOnClickListener {
            withSectionAccess(
                MainDeviceRegistry.ACCESS_SHARED,
                "Shared Media"
            ) {
                openRemoteUsbMusic()
            }
        }

    }

    override fun onResume() {
        super.onResume()
        SupremacyPlaybackService.resumeLastSessionIfNeeded(this)
        musicNowPlaying.removeCallbacks(compactPlayerRefresh)
        musicNowPlaying.postDelayed(compactPlayerRefresh, 250)

        playerBroadcastSessionId = UUID.randomUUID().toString()
        lastPlayerBroadcastId = ""
        playerBroadcastPollBusy = false
        musicNowPlaying.removeCallbacks(playerBroadcastPoll)
        musicNowPlaying.post(playerBroadcastPoll)
    }

    override fun onPause() {
        if (::musicNowPlaying.isInitialized) {
            musicNowPlaying.removeCallbacks(compactPlayerRefresh)
            musicNowPlaying.removeCallbacks(playerBroadcastPoll)
        }

        val closingSession = playerBroadcastSessionId
        playerBroadcastSessionId = ""
        playerBroadcastPollBusy = false
        if (closingSession.isNotBlank()) {
            playerBroadcastIo.execute {
                runCatching {
                    RemoteUsbMusicClient.closePlayerBroadcastSession(
                        this@MoviesActivity,
                        closingSession
                    )
                }
            }
        }
        super.onPause()
    }

    private fun broadcastCurrentTrack() {
        if (!MainDeviceRegistry.isLocallyOwner(this)) {
            Toast.makeText(
                this,
                "Alleen The One owner kan broadcast starten.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!SupremacyPlaybackService.isActive(this)) {
            Toast.makeText(
                this,
                "Start eerst een nummer in The One player.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val url = SupremacyPlaybackService.currentUrl(this)
        if (url.isBlank()) {
            Toast.makeText(
                this,
                "Dit nummer kan nog niet worden gebroadcast.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val title = SupremacyPlaybackService.currentTitle(this)
        val source = SupremacyPlaybackService.currentSource(this)
        val position = SupremacyPlaybackService.currentPositionMs(this)

        musicBroadcastButton.isEnabled = false
        playerBroadcastIo.execute {
            val result = runCatching {
                RemoteUsbMusicClient.publishPlayerBroadcast(
                    this@MoviesActivity,
                    title,
                    url,
                    source,
                    position
                )
            }

            runOnUiThread {
                musicBroadcastButton.isEnabled = true
                result.onSuccess { count ->
                    Toast.makeText(
                        this@MoviesActivity,
                        if (count == 0) {
                            "Geen andere The One players staan nu open."
                        } else {
                            "📡 Broadcast gestart naar $count player" +
                                if (count == 1) "." else "s."
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this@MoviesActivity,
                        error.message ?: "Broadcast starten mislukt",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openCurrentMusicSource() {
        if (!SupremacyPlaybackService.isActive(this)) return

        val source = SupremacyPlaybackService.currentSource(this)
        if (source.equals("The One Favorites", ignoreCase = true)) {
            withSectionAccess(
                MainDeviceRegistry.ACCESS_FAVORITES,
                "The One Favorites"
            ) {
                openTheOneFavorites()
            }
            return
        }
        if (!source.startsWith("Shared Media •", ignoreCase = true)) {
            withSectionAccess(
                MainDeviceRegistry.ACCESS_MIXES,
                "The One Mixes"
            ) {
                startActivity(
                    Intent(this, SupremacyMixesActivity::class.java)
                        .putExtra("focus_title", SupremacyPlaybackService.currentTitle(this))
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            return
        }

        val activeTitle =
            cleanUsbTrackTitle(SupremacyPlaybackService.currentTitle(this))
        val activeIndex = SupremacyPlaybackService.currentQueueIndex(this)

        withSectionAccess(
            MainDeviceRegistry.ACCESS_SHARED,
            "Shared Media"
        ) {
            openCurrentSharedMediaFolderAfterAccess(source, activeTitle, activeIndex)
        }
    }

    private fun openCurrentSharedMediaFolderAfterAccess(
        source: String,
        activeTitle: String,
        activeIndex: Int
    ) {
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
            val source = SupremacyPlaybackService.currentSource(this)
            val isSharedMedia = source.startsWith("Shared Media •", ignoreCase = true)
            musicNowPlaying.paintFlags =
                musicNowPlaying.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            musicNowPlaying.contentDescription =
                when {
                    source.equals("The One Favorites", ignoreCase = true) ->
                        "Tik om The One Favorites te openen"
                    isSharedMedia ->
                        "Tik om naar de map van het spelende nummer te gaan"
                    else ->
                        "Tik om het spelende nummer in The One Mixes te openen"
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

    private fun withSectionAccess(
        scope: String,
        label: String,
        onAllowed: () -> Unit
    ) {
        remoteMusicIo.execute {
            val status = try {
                MainDeviceRegistry.refreshAccess(this, scope)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (status?.allowed == true) {
                    onAllowed()
                } else {
                    showSectionAccessRequestDialog(
                        scope,
                        label,
                        status?.pending == true
                    )
                }
            }
        }
    }

    private fun showSectionAccessRequestDialog(
        scope: String,
        label: String,
        pending: Boolean
    ) {
        if (MainDeviceRegistry.isLocallyOwner(this)) return

        if (pending) {
            AlertDialog.Builder(this)
                .setTitle(label)
                .setMessage("Je aanvraag voor $label is al verstuurd en wacht op goedkeuring van The One.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage("Je hebt nog geen toegang tot $label. Wil je toegang aanvragen bij The One?")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Toegang aanvragen") { _, _ ->
                remoteMusicIo.execute {
                    val result = try {
                        MainDeviceRegistry.requestAccess(this, scope)
                    } catch (_: Exception) {
                        null
                    }
                    runOnUiThread {
                        when {
                            result?.allowed == true -> {
                                Toast.makeText(
                                    this,
                                    "Toegang is al toegestaan.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            result?.pending == true -> {
                                Toast.makeText(
                                    this,
                                    "Aanvraag voor $label is verstuurd naar The One.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            else -> {
                                Toast.makeText(
                                    this,
                                    "Aanvraag kon niet worden verstuurd.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
            .show()
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
                                cleanUsbTrackTitle(item.title)
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
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            setBackgroundResource(R.drawable.bg_the_one_panel)
        }

        panel.addView(TextView(this).apply {
            text = "THE ONE FAMILY • MUZIEK"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(Color.parseColor("#E8AA4E"))
        })
        panel.addView(TextView(this).apply {
            text = "★ The One Favorites"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#F3F8FC"))
            setPadding(0, dp(5), 0, dp(3))
        })
        panel.addView(TextView(this).apply {
            text = if (favorites.isEmpty()) "Nog geen favorieten" else "${favorites.size} favoriet" + if (favorites.size == 1) "" else "en"
            textSize = 13f
            setTextColor(Color.parseColor("#91A4BD"))
            setPadding(0, 0, 0, dp(14))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        favorites.forEach { item ->
            val playableEntry = playable.firstOrNull { it.first.id == item.id }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.bg_the_one_tile)
            }

            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(TextView(this).apply {
                text = item.title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                contentDescription = "Afspelen ${item.title}"
                setTextColor(Color.parseColor(if (playableEntry != null) "#F3F8FC" else "#8F9BAD"))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            copy.addView(TextView(this).apply {
                text = item.sourceLabel.ifBlank {
                    if (item.kind.equals("mix", true)) "The One Mixes" else "Shared Media"
                } + if (playableEntry == null) " • Bron niet beschikbaar" else ""
                textSize = 11f
                setTextColor(Color.parseColor("#91A4BD"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(TextView(this).apply {
                text = "★"
                textSize = 23f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#E8AA4E"))
                contentDescription = "Verwijder ${item.title} uit Favorites"
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    isEnabled = false
                    remoteMusicIo.execute {
                        val access = try {
                            MainDeviceRegistry.refreshAccess(
                                this@MoviesActivity,
                                MainDeviceRegistry.ACCESS_FAVORITES
                            )
                        } catch (_: Exception) { null }
                        val allowed = access?.allowed == true
                        val ok = if (allowed) try {
                            RemoteUsbMusicClient.setFavoriteItem(this@MoviesActivity, item, false)
                            true
                        } catch (_: Exception) { false } else false
                        runOnUiThread {
                            if (!allowed) {
                                showSectionAccessRequestDialog(
                                    MainDeviceRegistry.ACCESS_FAVORITES,
                                    "The One Favorites",
                                    access?.pending == true
                                )
                                isEnabled = true
                            } else if (ok) {
                                dialog.dismiss()
                                openTheOneFavorites()
                            } else {
                                Toast.makeText(this@MoviesActivity, "Favoriet verwijderen mislukt", Toast.LENGTH_SHORT).show()
                                isEnabled = true
                            }
                        }
                    }
                }
            })

            row.addView(TextView(this).apply {
                text = if (playableEntry != null) "▶" else "…"
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor(if (playableEntry != null) "#20B8FF" else "#8F9BAD"))
                setPadding(dp(10), dp(8), dp(6), dp(8))
                setOnClickListener {
                    val selected = playableEntry ?: return@setOnClickListener
                    val index = playable.indexOfFirst { it.first.id == selected.first.id }
                    SupremacyPlaybackService.playQueue(
                        this@MoviesActivity,
                        playable.map { it.second },
                        playable.map { it.third },
                        index.coerceAtLeast(0),
                        "The One Favorites"
                    )
                    dialog.dismiss()
                    musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 150)
                }
            })

            if (playableEntry != null) {
                row.setOnClickListener {
                    val index = playable.indexOfFirst { it.first.id == playableEntry.first.id }
                    SupremacyPlaybackService.playQueue(
                        this@MoviesActivity,
                        playable.map { it.second },
                        playable.map { it.third },
                        index.coerceAtLeast(0),
                        "The One Favorites"
                    )
                    dialog.dismiss()
                    musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 150)
                }
            }

            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(9) }
            )
        }

        if (favorites.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Markeer bij Shared Media of The One Mixes een nummer met ☆ om het hier toe te voegen."
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#91A4BD"))
                setPadding(dp(14), dp(28), dp(14), dp(28))
            })
        }

        panel.addView(
            ScrollView(this).apply { isFillViewport = true; addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        panel.addView(TextView(this).apply {
            text = "SLUITEN"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#E8AA4E"))
            setBackgroundResource(R.drawable.bg_the_one_gold_outline)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.86f).toInt()
        )
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
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(20))
            setBackgroundResource(R.drawable.bg_the_one_panel)
        }

        panel.addView(
            TextView(this).apply {
                text = "THE ONE FAMILY • MEDIA"
                textSize = 12f
                letterSpacing = 0.18f
                setTextColor(Color.parseColor("#E8AA4E"))
            }
        )
        panel.addView(
            TextView(this).apply {
                text = "Shared Media"
                textSize = 28f
                setTextColor(Color.parseColor("#F3F8FC"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(2))
            }
        )
        panel.addView(
            TextView(this).apply {
                text = "Kies een bron"
                textSize = 14f
                setTextColor(Color.parseColor("#91A4BD"))
                setPadding(0, 0, 0, dp(18))
            }
        )

        val sourceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
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
            val cached = stick.files.count { file -> file.cached }
            val total = stick.totalFiles
            val status = if (cached >= total && total > 0) {
                "$cached nummer" + if (cached == 1) "" else "s"
            } else {
                "$cached van $total beschikbaar"
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(16), dp(16))
                setBackgroundResource(R.drawable.bg_the_one_tile)
                isClickable = true
                isFocusable = true
            }

            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            copy.addView(
                TextView(this).apply {
                    text = remoteStickDisplayName(stick)
                    textSize = 18f
                    setTextColor(Color.parseColor("#F3F8FC"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
            copy.addView(
                TextView(this).apply {
                    text = status
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#91A4BD"))
                    setPadding(0, dp(4), 0, 0)
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
                    textSize = 30f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#E8AA4E"))
                    setPadding(dp(12), 0, dp(2), 0)
                }
            )

            card.setOnClickListener {
                dialog.dismiss()
                if (stick.files.none { it.cached }) {
                    AlertDialog.Builder(this@MoviesActivity)
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
                    bottomMargin = dp(12)
                }
            )
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        val close = TextView(this).apply {
            text = "SLUITEN"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#E8AA4E"))
            setBackgroundResource(R.drawable.bg_the_one_gold_outline)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { dialog.dismiss() }
        }
        val refresh = TextView(this).apply {
            text = "VERNIEUWEN"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#201505"))
            setBackgroundResource(R.drawable.bg_the_one_gold_button)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener {
                dialog.dismiss()
                loadRemoteUsbCatalog()
            }
        }
        actions.addView(
            close,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        )
        actions.addView(
            refresh,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
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
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    private fun normalizeRemoteFolder(value: String): String =
        value.replace('\\', '/').trim('/').let { if (it.equals("Hoofdmap", true)) "" else it }

    private fun parentRemoteFolder(value: String): String {
        val folder = normalizeRemoteFolder(value)
        return folder.substringBeforeLast('/', "")
    }

    private fun showRemoteFolderDialog(stick: RemoteUsbMusicClient.RemoteStick) {
        val firstSegments = (
            stick.folders.map { normalizeRemoteFolder(it) } +
                stick.files.map { normalizeRemoteFolder(it.folder) }
        )
            .filter { it.isNotBlank() }
            .map { it.substringBefore('/') }
            .distinctBy { it.lowercase() }

        val startFolder = if (firstSegments.size == 1) {
            firstSegments.first()
        } else {
            ""
        }

        showRemoteFolderLevel(stick, startFolder)
    }

    private fun showFreshRemoteFolderLevel(
        stick: RemoteUsbMusicClient.RemoteStick,
        folder: String
    ) {
        remoteMusicIo.execute {
            val freshStick = runCatching {
                val refreshed = RemoteUsbMusicClient.catalog(this@MoviesActivity)
                refreshed.firstOrNull {
                    it.deviceId.equals(stick.deviceId, ignoreCase = true) &&
                        it.stickId.equals(stick.stickId, ignoreCase = true)
                }
            }.getOrNull()

            runOnUiThread {
                showRemoteFolderLevel(freshStick ?: stick, folder)
            }
        }
    }

    private fun showRemoteFolderLevel(
        stick: RemoteUsbMusicClient.RemoteStick,
        folder: String,
        openCurrentFolder: Boolean = false
    ) {
        val normalized = normalizeRemoteFolder(folder)
        val canOrganizeNewDownloads =
            MainDeviceRegistry.hasAccess(this, MainDeviceRegistry.ACCESS_DOWNLOADS)
        val isNewDownloadsFolder =
            normalized.equals("Nieuwe downloads", ignoreCase = true) ||
                normalized.startsWith("Nieuwe downloads/", ignoreCase = true)
        val directFiles = stick.files
            .filter { normalizeRemoteFolder(it.folder) == normalized }
            .sortedBy { it.name.lowercase() }

        if (openCurrentFolder && directFiles.isNotEmpty()) {
            showRemoteTrackDialog(stick, normalized, directFiles)
            return
        }

        val prefix = if (normalized.isBlank()) "" else "$normalized/"
        val knownFolders = (
            stick.folders.map { normalizeRemoteFolder(it) } +
                stick.files.map { normalizeRemoteFolder(it.folder) }
        )
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val childFolders = knownFolders
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

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            setBackgroundResource(R.drawable.bg_the_one_panel)
        }

        panel.addView(
            TextView(this).apply {
                text = "THE ONE FAMILY • SHARED MEDIA"
                textSize = 11f
                letterSpacing = 0.16f
                setTextColor(Color.parseColor("#E8AA4E"))
            }
        )
        panel.addView(
            TextView(this).apply {
                text = if (normalized.isBlank()) {
                    remoteStickDisplayName(stick)
                } else {
                    normalized.substringAfterLast('/')
                }
                textSize = 25f
                setTextColor(Color.parseColor("#F3F8FC"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, dp(4))
            }
        )
        panel.addView(
            TextView(this).apply {
                text = if (normalized.isBlank()) {
                    "Mappen"
                } else {
                    normalized
                }
                textSize = 20f
                setTextColor(Color.parseColor("#91A4BD"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
                setPadding(0, 0, 0, dp(14))
            }
        )

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        childFolders.forEach { child ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(14), dp(14))
                setBackgroundResource(R.drawable.bg_the_one_tile)
                isClickable = true
                isFocusable = true
            }
            card.addView(
                TextView(this).apply {
                    text = "▣"
                    textSize = 19f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#20B8FF"))
                },
                LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            card.addView(
                TextView(this).apply {
                    text = child.substringAfterLast('/')
                    textSize = 18f
                    setTextColor(Color.parseColor("#F3F8FC"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            card.addView(
                TextView(this).apply {
                    text = "›"
                    textSize = 29f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#E8AA4E"))
                    setPadding(dp(10), 0, 0, 0)
                }
            )
            card.setOnClickListener {
                dialog.dismiss()
                if (child.equals("Nieuwe downloads", ignoreCase = true)) {
                    showFreshRemoteFolderLevel(stick, child)
                } else {
                    showRemoteFolderLevel(stick, child)
                }
            }
            list.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }

        directFiles.forEachIndexed { index, file ->
            val current = isRemoteUsbTrackCurrent(stick, file)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(14), dp(13))
                setBackgroundResource(R.drawable.bg_the_one_tile)
                isClickable = true
                isFocusable = true
            }
            row.addView(
                TextView(this).apply {
                    text = if (current) "▶" else "♪"
                    textSize = 15.5f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor(if (current) "#E8AA4E" else "#20B8FF"))
                },
                LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            row.addView(
                TextView(this).apply {
                    text = (if (current) "NU • " else "") + cleanUsbTrackTitle(file.displayName)
                    textSize = 16f
                    setTextColor(Color.parseColor(if (current) "#E8AA4E" else "#F3F8FC"))
                    setTypeface(
                        null,
                        if (current) android.graphics.Typeface.BOLD
                        else android.graphics.Typeface.NORMAL
                    )
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (isNewDownloadsFolder && canOrganizeNewDownloads) {
                row.addView(
                    TextView(this).apply {
                        text = "→"
                        textSize = 22f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.parseColor("#20B8FF"))
                        contentDescription = "Verplaats ${file.name}"
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        setOnClickListener {
                            showNewDownloadDestinationDialog(
                                stick,
                                file,
                                dialog,
                                normalized
                            )
                        }
                    },
                    LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
                )
            }

            row.addView(
                TextView(this).apply {
                    text = if (favoriteUsbKeys.contains(usbFavoriteKey(file))) "★" else "☆"
                    textSize = 22f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#D8A451"))
                    contentDescription = "Favoriet ${file.name}"
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener {
                        toggleUsbFavorite(stick, file, this)
                    }
                },
                LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            row.addView(
                TextView(this).apply {
                    text = "▶"
                    textSize = 17f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#20B8FF"))
                    setPadding(dp(8), dp(8), dp(4), dp(8))
                }
            )
            row.setOnClickListener {
                dialog.dismiss()
                playRemoteUsbFolder(stick, directFiles, index, normalized)
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }

        if (childFolders.isEmpty() && directFiles.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = "Deze map bevat nog geen beschikbare nummers."
                    textSize = 15f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#91A4BD"))
                    setPadding(dp(16), dp(28), dp(16), dp(28))
                    setBackgroundResource(R.drawable.bg_the_one_tile)
                }
            )
        }

        panel.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(list)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        val back = TextView(this).apply {
            text = if (normalized.isBlank()) "SLUITEN" else "← 1 STAP TERUG"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#E8AA4E"))
            setBackgroundResource(R.drawable.bg_the_one_gold_outline)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                dialog.dismiss()
                if (normalized.isNotBlank()) {
                    showRemoteFolderLevel(stick, parentRemoteFolder(normalized))
                }
            }
        }
        val player = TextView(this).apply {
            text = "NAAR PLAYER  ›"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#201505"))
            setBackgroundResource(R.drawable.bg_the_one_gold_button)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                dialog.dismiss()
            }
        }
        actions.addView(
            back,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(7)
            }
        )
        actions.addView(
            player,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(7)
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
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.86f).toInt()
        )
    }

    private fun showNewDownloadDestinationDialog(
        stick: RemoteUsbMusicClient.RemoteStick,
        file: RemoteUsbMusicClient.RemoteFile,
        parentDialog: Dialog,
        sourceFolder: String
    ) {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val folders = (
            stick.folders +
                stick.files.flatMap { remoteFile ->
                    val full = normalizeRemoteFolder(remoteFile.folder)
                    val parts = full.split('/').filter { it.isNotBlank() }
                    (1..parts.size).map { depth ->
                        parts.take(depth).joinToString("/")
                    }
                }
        )
            .map { normalizeRemoteFolder(it) }
            .filter {
                it.isNotBlank() &&
                    !it.equals("Nieuwe downloads", ignoreCase = true) &&
                    !it.startsWith("Nieuwe downloads/", ignoreCase = true) &&
                    (it.equals("Ruben", ignoreCase = true) ||
                        it.startsWith("Ruben/", ignoreCase = true))
            }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        if (folders.isEmpty()) {
            Toast.makeText(
                this,
                "Geen doelmappen gevonden in Shared Media.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            setBackgroundResource(R.drawable.bg_the_one_panel)
        }

        panel.addView(TextView(this).apply {
            text = "NUMMER VERPLAATSEN"
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(Color.parseColor("#E8AA4E"))
        })
        panel.addView(TextView(this).apply {
            text = cleanUsbTrackTitle(file.displayName)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(4))
        })
        panel.addView(TextView(this).apply {
            text = "Kies de map waar dit nummer naartoe moet."
            textSize = 13f
            setTextColor(Color.parseColor("#91A4BD"))
            setPadding(0, 0, 0, dp(12))
        })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        folders.forEach { folder ->
            val label = folder.removePrefix("Ruben/").ifBlank { "Ruben" }
            list.addView(
                TextView(this).apply {
                    text = "📁  $label"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_the_one_tile)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    setOnClickListener {
                        isEnabled = false
                        remoteMusicIo.execute {
                            val result = runCatching {
                                RemoteUsbMusicClient.moveNewDownload(
                                    this@MoviesActivity,
                                    file,
                                    folder
                                )
                            }
                            runOnUiThread {
                                result.onSuccess {
                                    dialog.dismiss()
                                    Toast.makeText(
                                        this@MoviesActivity,
                                        "Verplaatsen naar $label…",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                    remoteMusicIo.execute {
                                        var refreshedStick: RemoteUsbMusicClient.RemoteStick? = null
                                        repeat(8) {
                                            if (refreshedStick != null) return@repeat
                                            try {
                                                Thread.sleep(if (it == 0) 1200L else 1500L)
                                                val refreshed = RemoteUsbMusicClient.catalog(
                                                    this@MoviesActivity
                                                )
                                                val sameStick = refreshed.firstOrNull {
                                                    it.deviceId.equals(stick.deviceId, true) &&
                                                        it.stickId.equals(stick.stickId, true)
                                                }
                                                val sourceStillVisible =
                                                    sameStick?.files?.any {
                                                        it.path.equals(file.path, true)
                                                    } == true
                                                if (sameStick != null && !sourceStillVisible) {
                                                    refreshedStick = sameStick
                                                }
                                            } catch (_: Exception) {
                                            }
                                        }

                                        runOnUiThread {
                                            parentDialog.dismiss()
                                            val ready = refreshedStick
                                            if (ready != null) {
                                                Toast.makeText(
                                                    this@MoviesActivity,
                                                    "Nummer verplaatst naar $label",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                showRemoteFolderLevel(
                                                    ready,
                                                    sourceFolder
                                                )
                                            } else {
                                                Toast.makeText(
                                                    this@MoviesActivity,
                                                    "Verplaatsing verwerkt. Nieuwe downloads vernieuwen…",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                showFreshRemoteFolderLevel(
                                                    stick,
                                                    sourceFolder
                                                )
                                            }
                                        }
                                    }
                                }.onFailure {
                                    isEnabled = true
                                    Toast.makeText(
                                        this@MoviesActivity,
                                        it.message ?: "Verplaatsen mislukt",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }

        panel.addView(
            ScrollView(this).apply {
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
            text = "ANNULEREN"
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#E8AA4E"))
            setBackgroundResource(R.drawable.bg_the_one_gold_outline)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.84f).toInt()
        )
    }

    private fun showRemoteTrackDialog(
        stick: RemoteUsbMusicClient.RemoteStick,
        folder: String,
        files: List<RemoteUsbMusicClient.RemoteFile>
    ) {
        val normalized = normalizeRemoteFolder(folder)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val actionWidth = dp(40)
        val actionHeight = dp(38)
        val actionGap = dp(6)
        val canDeleteSharedMedia = MainDeviceRegistry.isLocallyOwner(this)
        val canOrganizeNewDownloads =
            MainDeviceRegistry.hasAccess(this, MainDeviceRegistry.ACCESS_DOWNLOADS)
        val dialog = Dialog(this)
        val canDjImport =
            MainDeviceRegistry.isTheOneProfile(this) ||
            MainDeviceRegistry.hasAccess(
                this,
                MainDeviceRegistry.ACCESS_DJ
            )
        val trackRows = mutableListOf<LinearLayout>()
        val trackNumbers = mutableListOf<TextView>()
        val trackTitles = mutableListOf<TextView>()
        val trackPlayButtons = mutableListOf<TextView>()

        files.forEachIndexed { index, file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }

            val infoRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.TOP
            }

            val number = TextView(this).apply {
                text = (index + 1).toString().padStart(2, '0')
                textSize = 12f
                setTextColor(Color.parseColor("#20B8FF"))
                gravity = android.view.Gravity.CENTER
                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
            }
            infoRow.addView(
                number,
                LinearLayout.LayoutParams(
                    dp(34),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val metadata = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(6),
                    0,
                    dp(4),
                    0
                )
            }

            val title = TextView(this).apply {
                text = if (file.cached) {
                    cleanUsbTrackTitle(file.displayName)
                } else {
                    cleanUsbTrackTitle(file.displayName) + "  •  Synchroniseren…"
                }
                textSize = 17f
                setTextColor(
                    Color.parseColor(if (file.cached) "#FFFFFF" else "#8F9BAD")
                )
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            metadata.addView(
                title,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            metadata.addView(
                TextView(this).apply {
                    text = file.artist.ifBlank { "Artiest onbekend" }
                    textSize = 13f
                    setTextColor(Color.parseColor("#91A4BD"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(
                        0,
                        dp(2),
                        0,
                        0
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            infoRow.addView(
                metadata,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            val isNewDownload =
                normalized.equals("Nieuwe downloads", ignoreCase = true)
            if (isNewDownload && canOrganizeNewDownloads) {
                val move = TextView(this).apply {
                    text = "→"
                    textSize = 22f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#20B8FF"))
                    contentDescription = "Verplaats ${file.name}"
                    setBackgroundResource(R.drawable.bg_the_one_blue_button)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        showNewDownloadDestinationDialog(
                            stick,
                            file,
                            dialog,
                            normalized
                        )
                    }
                }
                infoRow.addView(
                    move,
                    LinearLayout.LayoutParams(
                        dp(42),
                        dp(38)
                    ).apply { marginStart = dp(6) }
                )
            }

            if (canDeleteSharedMedia) {
                val info = TextView(this).apply {
                    text = "ⓘ"
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.parseColor("#91A4BD"))
                    contentDescription = "Beheer ${file.name}"
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnClickListener {
                        AlertDialog.Builder(this@MoviesActivity)
                            .setTitle(cleanUsbTrackTitle(file.displayName))
                            .setMessage(
                                "Dit nummer verwijderen uit Shared Media? " +
                                    "De bron op USB/Windows blijft bestaan, maar dit nummer " +
                                    "komt niet opnieuw terug bij synchronisatie."
                            )
                            .setNegativeButton("Annuleren", null)
                            .setPositiveButton("Verwijderen") { _, _ ->
                                remoteMusicIo.execute {
                                    try {
                                        val freed = RemoteUsbMusicClient.deleteSharedTrack(
                                            this@MoviesActivity,
                                            file
                                        )
                                        val refreshed = RemoteUsbMusicClient.catalog(
                                            this@MoviesActivity
                                        )
                                        val refreshedStick = refreshed.firstOrNull {
                                            it.deviceId.equals(stick.deviceId, true) &&
                                                it.stickId.equals(stick.stickId, true)
                                        }

                                        runOnUiThread {
                                            dialog.dismiss()
                                            val freedText = if (freed > 0L) {
                                                val mb = freed.toDouble() / 1024.0 / 1024.0
                                                " • %.1f MB vrij".format(mb)
                                            } else {
                                                ""
                                            }
                                            Toast.makeText(
                                                this@MoviesActivity,
                                                "Verwijderd uit Shared Media$freedText",
                                                Toast.LENGTH_SHORT
                                            ).show()

                                            if (refreshedStick != null) {
                                                showRemoteFolderLevel(
                                                    refreshedStick,
                                                    normalized,
                                                    openCurrentFolder = true
                                                )
                                            } else {
                                                loadRemoteUsbCatalog()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        runOnUiThread {
                                            Toast.makeText(
                                                this@MoviesActivity,
                                                e.message ?: "Verwijderen mislukt",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                            .show()
                    }
                }
                infoRow.addView(
                    info,
                    LinearLayout.LayoutParams(
                        dp(34),
                        dp(34)
                    )
                )
            }

            row.addView(
                infoRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(40), dp(7), 0, 0)
            }

            val play = TextView(this).apply {
                text = if (file.cached) "▶" else "…"
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    Color.parseColor(if (file.cached) "#20B8FF" else "#8F9BAD")
                )
                setBackgroundResource(R.drawable.bg_the_one_blue_button)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    if (file.cached) {
                        playRemoteUsbFolder(stick, files, index, normalized)
                    } else {
                        Toast.makeText(
                            this@MoviesActivity,
                            "Dit nummer wordt nog gesynchroniseerd.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            actions.addView(
                play,
                LinearLayout.LayoutParams(
                    actionWidth,
                    actionHeight
                ).apply {
                    marginEnd = actionGap
                }
            )

            val favorite = TextView(this).apply {
                text = if (favoriteUsbKeys.contains(usbFavoriteKey(file))) "★" else "☆"
                textSize = 23f
                setTextColor(Color.parseColor("#D8A451"))
                gravity = android.view.Gravity.CENTER
                contentDescription = "Favoriet ${file.name}"
                setBackgroundResource(R.drawable.bg_the_one_gold_outline)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    toggleUsbFavorite(stick, file, this)
                }
            }
            actions.addView(
                favorite,
                LinearLayout.LayoutParams(
                    actionWidth,
                    actionHeight
                ).apply {
                    marginEnd = actionGap
                }
            )

            val download = TextView(this).apply {
                text = if (file.cached) "↓" else "…"
                textSize = 21f
                setTextColor(Color.parseColor("#D8A451"))
                gravity = android.view.Gravity.CENTER
                contentDescription = "Download ${file.name}"
                setBackgroundResource(R.drawable.bg_the_one_gold_outline)
                setPadding(0, 0, 0, 0)
                alpha = if (file.cached) 1f else 0.35f
                setOnClickListener {
                    if (file.cached) {
                        requestRemoteUsbDownload(file)
                    } else {
                        Toast.makeText(
                            this@MoviesActivity,
                            "Download beschikbaar zodra synchronisatie klaar is.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            actions.addView(
                download,
                LinearLayout.LayoutParams(
                    actionWidth,
                    actionHeight
                ).apply {
                    if (canDjImport) marginEnd = actionGap
                }
            )

            if (canDjImport) {
                val dj = TextView(this).apply {
                    text = "DJ"
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#20B8FF"))
                    gravity = android.view.Gravity.CENTER
                    contentDescription = "Stuur ${file.name} naar The One DJ"
                    setBackgroundResource(R.drawable.bg_the_one_blue_button)
                    setPadding(0, 0, 0, 0)
                    alpha = if (file.cached) 1f else 0.35f
                    setOnClickListener {
                        if (file.cached) {
                            requestRemoteUsbDjImport(file, this)
                        } else {
                            Toast.makeText(
                                this@MoviesActivity,
                                "DJ-import beschikbaar zodra synchronisatie klaar is.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                actions.addView(
                    dj,
                    LinearLayout.LayoutParams(
                        actionWidth,
                        actionHeight
                    )
                )
            }

            row.addView(
                actions,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
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
                        cleanUsbTrackTitle(file.displayName) +
                        if (!file.cached) "  •  Synchroniseren…" else ""
                trackTitles[index].setTextColor(
                    Color.parseColor(if (isCurrent) "#D8A451" else "#FFFFFF")
                )
                trackTitles[index].setTypeface(
                    null,
                    if (isCurrent) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL
                )
                trackPlayButtons[index].text =
                    when {
                        isCurrent -> "▶"
                        file.cached -> "▶"
                        else -> "…"
                    }
                trackPlayButtons[index].setTextColor(
                    Color.parseColor(
                        when {
                            isCurrent -> "#D8A451"
                            file.cached -> "#20B8FF"
                            else -> "#8F9BAD"
                        }
                    )
                )
                trackRows[index].contentDescription =
                    if (isCurrent) "Nu actief: ${cleanUsbTrackTitle(file.displayName)}"
                    else cleanUsbTrackTitle(file.displayName)
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            setBackgroundResource(R.drawable.bg_the_one_panel)
        }

        panel.addView(TextView(this).apply {
            text = "THE ONE FAMILY • SHARED MEDIA"
            textSize = 10.5f
            letterSpacing = 0.14f
            setTextColor(Color.parseColor("#E8AA4E"))
        })
        panel.addView(TextView(this).apply {
            text = if (normalized.isBlank()) remoteStickDisplayName(stick)
                   else normalized.substringAfterLast('/')
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#F3F8FC"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(10))
        })

        panel.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(trackList)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        footer.addView(
            TextView(this).apply {
                text = if (normalized.isBlank()) "SLUITEN" else "← TERUG"
                textSize = 12.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#E8AA4E"))
                setBackgroundResource(R.drawable.bg_the_one_gold_outline)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    dialog.dismiss()
                    if (normalized.isNotBlank()) {
                        showRemoteFolderLevel(stick, parentRemoteFolder(normalized))
                    }
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
        )
        footer.addView(
            TextView(this).apply {
                text = "NAAR PLAYER ›"
                textSize = 12.5f
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#201505"))
                setBackgroundResource(R.drawable.bg_the_one_gold_button)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(5)
            }
        )
        panel.addView(footer)

        val liveHighlight = object : Runnable {
            override fun run() {
                if (dialog.isShowing && !isFinishing && !isDestroyed) {
                    refreshPlayingTrackHighlight()
                    trackList.postDelayed(this, 500L)
                }
            }
        }

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            refreshPlayingTrackHighlight()
            trackList.postDelayed(liveHighlight, 500L)
        }
        dialog.setOnDismissListener {
            trackList.removeCallbacks(liveHighlight)
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes = dialog.window?.attributes?.apply { dimAmount = 0.72f }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.88f).toInt()
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
            val access = try {
                MainDeviceRegistry.refreshAccess(
                    this,
                    MainDeviceRegistry.ACCESS_FAVORITES
                )
            } catch (_: Exception) {
                null
            }
            val allowed = access?.allowed == true
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
            } else {
                false
            }

            runOnUiThread {
                button.isEnabled = true
                if (!allowed) {
                    showSectionAccessRequestDialog(
                        MainDeviceRegistry.ACCESS_FAVORITES,
                        "The One Favorites",
                        access?.pending == true
                    )
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

    private fun requestRemoteUsbDjImport(
        file: RemoteUsbMusicClient.RemoteFile,
        button: TextView
    ) {
        button.isEnabled = false
        remoteMusicIo.execute {
            val access = try {
                MainDeviceRegistry.refreshAccess(
                    this,
                    MainDeviceRegistry.ACCESS_DJ
                )
            } catch (_: Exception) {
                null
            }

            val result = if (access?.allowed == true) {
                try {
                    if (!RemoteUsbMusicClient.hasToken(this) &&
                        !RemoteUsbMusicClient.loginForBrowsing(this)
                    ) {
                        false
                    } else {
                        RemoteUsbMusicClient.queueDjImport(this, file)
                    }
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }

            runOnUiThread {
                button.isEnabled = true
                if (access?.allowed != true) {
                    Toast.makeText(
                        this,
                        "Geen toestemming voor The One DJ import.",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (result) {
                    Toast.makeText(
                        this,
                        "Naar The One DJ gestuurd: ${cleanUsbTrackTitle(file.displayName)}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Naar DJ sturen mislukt.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun requestRemoteUsbDownload(file: RemoteUsbMusicClient.RemoteFile) {
        remoteMusicIo.execute {
            val access = try {
                MainDeviceRegistry.refreshAccess(
                    this,
                    MainDeviceRegistry.ACCESS_SHARED
                )
            } catch (_: Exception) {
                null
            }
            val allowed = access?.allowed == true
            runOnUiThread {
                if (!allowed) {
                    showSectionAccessRequestDialog(
                        MainDeviceRegistry.ACCESS_SHARED,
                        "Shared Media",
                        access?.pending == true
                    )
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
            "Shared Media • " + remoteStickDisplayName(stick)
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

            val urls = ArrayList(playableFiles.map { RemoteUsbMusicClient.streamUrl(this, it) })
            val titles = ArrayList(playableFiles.map { cleanUsbTrackTitle(it.displayName) })

            ContextCompat.startForegroundService(
                this,
                Intent(this, SupremacyPlaybackService::class.java).apply {
                    action = SupremacyPlaybackService.ACTION_PLAY
                    putStringArrayListExtra(SupremacyPlaybackService.EXTRA_QUEUE_URLS, urls)
                    putStringArrayListExtra(SupremacyPlaybackService.EXTRA_QUEUE_TITLES, titles)
                    putExtra(SupremacyPlaybackService.EXTRA_INDEX, playableIndex)
                    putExtra(
                        SupremacyPlaybackService.EXTRA_SOURCE,
                        "Shared Media • " + remoteStickDisplayName(stick)
                    )
                }
            )

            currentRemoteUsbStick = stick
            currentRemoteUsbFiles = playableFiles.toList()
            currentRemoteUsbFolder = normalizeRemoteFolder(folder)
            pendingMusicTitle = cleanUsbTrackTitle(selectedFile.displayName)
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
            musicNowPlaying.removeCallbacks(playerBroadcastPoll)
        }
        remoteMusicIo.shutdownNow()
        playerBroadcastIo.shutdownNow()
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
