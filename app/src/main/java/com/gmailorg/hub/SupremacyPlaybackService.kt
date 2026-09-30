package com.gmailorg.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

class SupremacyPlaybackService : Service() {
    private var player: ExoPlayer? = null
    private var currentTitle = "The One Mixes"
    private var currentSource = "The One Mixes"
    private var titles = arrayListOf<String>()
    private var urls = arrayListOf<String>()
    private var index = 0
    private var paused = false
    private var requestedStartPositionMs = 0
    private var requestedAutoStart = true
    private val stateHandler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            if (urls.isNotEmpty() && index in urls.indices) {
                saveSession()
            }
            stateHandler.postDelayed(this, 2500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        stateHandler.post(saveTick)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "The One Mixes", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // STOP is definitief: wis de afspeelsessie zodat terugkeren naar
                // Media of een nieuwe app-/autorun het nummer niet herstelt.
                stopPlayer()
                urls.clear()
                titles.clear()
                index = 0
                requestedStartPositionMs = 0
                requestedAutoStart = false
                paused = false
                currentTitle = "Geen muziek actief"
                currentSource = ""
                pendingQueueUrls = null
                pendingQueueTitles = null
                pendingQueueSource = null
                clearSession()
                saveState(active = false, playing = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE -> togglePlayback()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_SEEK -> seekToPosition(
                intent.getIntExtra(EXTRA_POSITION, 0)
            )
            ACTION_RESTORE_LAST -> restoreLastSession()

            ACTION_PLAY -> {
                val pendingUrlsNow = pendingQueueUrls
                val pendingTitlesNow = pendingQueueTitles
                val pendingSourceNow = pendingQueueSource
                val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

                if (!pendingUrlsNow.isNullOrEmpty()) {
                    urls = ArrayList(pendingUrlsNow)
                    titles = ArrayList(
                        pendingTitlesNow
                            ?.takeIf { it.size == pendingUrlsNow.size }
                            ?: pendingUrlsNow.map { "Muziek" }
                    )
                    currentSource =
                        pendingSourceNow.orEmpty().ifBlank { "The One Mixes" }
                    index = startIndex.coerceIn(0, urls.lastIndex)

                    pendingQueueUrls = null
                    pendingQueueTitles = null
                    pendingQueueSource = null
                } else {
                    currentSource =
                        intent.getStringExtra(EXTRA_SOURCE)
                            .orEmpty()
                            .ifBlank { "The One Mixes" }

                    val incomingUrls =
                        intent.getStringArrayListExtra(EXTRA_QUEUE_URLS)
                    val incomingTitles =
                        intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES)

                    if (!incomingUrls.isNullOrEmpty()) {
                        urls = ArrayList(incomingUrls)
                        titles = ArrayList(
                            incomingTitles
                                ?.takeIf { it.size == incomingUrls.size }
                                ?: incomingUrls.map { "The One Mixes" }
                        )
                        index = startIndex.coerceIn(0, urls.lastIndex)
                    } else {
                        val url =
                            intent.getStringExtra(EXTRA_URL).orEmpty()
                        val title =
                            intent.getStringExtra(EXTRA_TITLE)
                                .orEmpty()
                                .ifBlank { "The One Mixes" }
                        if (url.isBlank()) return START_NOT_STICKY
                        urls = arrayListOf(url)
                        titles = arrayListOf(title)
                        index = 0
                    }
                }

                requestedStartPositionMs =
                    intent.getIntExtra(EXTRA_POSITION, 0).coerceAtLeast(0)
                requestedAutoStart = true
                startCurrent()
            }

            null -> {
                if (urls.isEmpty()) restoreLastSession()
            }
        }
        return START_STICKY
    }

    private fun startCurrent() {
        if (urls.isEmpty() || index !in urls.indices) return
        currentTitle = titles.getOrNull(index).orEmpty().ifBlank { "The One Mixes" }
        startPlayback(urls[index], requestedStartPositionMs, requestedAutoStart)
    }

    private fun startPlayback(
        url: String,
        startPositionMs: Int = 0,
        autoStart: Boolean = true
    ) {
        stopPlayer()
        requestedStartPositionMs = startPositionMs.coerceAtLeast(0)
        requestedAutoStart = autoStart
        paused = !autoStart
        saveState(active = true, playing = false)
        saveSession(
            explicitPosition = requestedStartPositionMs,
            explicitPlaying = autoStart
        )
        startForeground(NOTIFICATION_ID, notification("Laden…"))

        val exo = ExoPlayer.Builder(this).build()
        player = exo

        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )

        // Eén doorlopende playlist voorkomt de harde stop tussen nummers.
        // ExoPlayer kan het volgende item vooraf voorbereiden en zonder het
        // volledig opnieuw opbouwen van de speler doorschakelen.
        val items = urls.map { MediaItem.fromUri(it) }
        if (items.isEmpty()) return
        exo.setMediaItems(items)
        exo.seekTo(index.coerceIn(0, items.lastIndex), requestedStartPositionMs.toLong())
        exo.playWhenReady = autoStart

        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player !== exo) return
                index = exo.currentMediaItemIndex.coerceIn(0, urls.lastIndex)
                requestedStartPositionMs = 0
                currentTitle = titles.getOrNull(index).orEmpty().ifBlank { "Muziek" }
                saveState(active = true, playing = exo.isPlaying || exo.playWhenReady)
                saveSession()
                updateNotification(if (exo.isPlaying || exo.playWhenReady) "Speelt af" else "Gepauzeerd")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== exo) return

                when (playbackState) {
                    Player.STATE_READY -> {
                        paused = !exo.playWhenReady
                        saveState(
                            active = true,
                            playing = exo.isPlaying || exo.playWhenReady
                        )
                        saveSession()
                        updateNotification(
                            if (exo.isPlaying || exo.playWhenReady) {
                                "Speelt af"
                            } else {
                                "Gepauzeerd"
                            }
                        )
                    }

                    Player.STATE_ENDED -> {
                        clearSession()
                        saveState(active = false, playing = false)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== exo) return
                paused = !isPlaying
                saveState(active = true, playing = isPlaying)
                saveSession()
                updateNotification(
                    if (isPlaying) "Speelt af" else "Gepauzeerd"
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exo) return
                saveState(active = false, playing = false)
                updateNotification("Afspelen mislukt")
            }
        })

        try {
            exo.prepare()
        } catch (_: Exception) {
            saveState(active = false, playing = false)
            stopPlayer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            paused = true
        } else {
            p.play()
            paused = false
        }
        saveState(active = true, playing = p.isPlaying)
        saveSession()
        updateNotification(if (p.isPlaying) "Speelt af" else "Gepauzeerd")
    }

    private fun next() {
        val p = player ?: return
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
            p.play()
        }
    }

    private fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3000L || !p.hasPreviousMediaItem()) {
            p.seekTo(0L)
            p.play()
        } else {
            p.seekToPreviousMediaItem()
            p.play()
        }
    }

    private fun seekToPosition(positionMs: Int) {
        val exo = player ?: return
        try {
            val duration = exo.duration
            val requested = positionMs.toLong().coerceAtLeast(0L)
            val safe = if (duration > 0 && duration != C.TIME_UNSET) {
                requested.coerceAtMost(duration)
            } else {
                requested
            }
            exo.seekTo(safe)
            requestedStartPositionMs =
                safe.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            saveSession(explicitPosition = requestedStartPositionMs)
        } catch (_: Exception) {
        }
    }

    private fun playbackPositionMs(): Int =
        try {
            player?.currentPosition
                ?.coerceAtLeast(0L)
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: requestedStartPositionMs
        } catch (_: Exception) {
            requestedStartPositionMs
        }.coerceAtLeast(0)

    private fun playbackDurationMs(): Int =
        try {
            val duration = player?.duration ?: 0L
            if (duration > 0 && duration != C.TIME_UNSET) {
                duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }

    private fun notification(state: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MoviesActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun serviceAction(requestCode: Int, actionName: String): PendingIntent =
            PendingIntent.getService(
                this,
                requestCode,
                Intent(this, SupremacyPlaybackService::class.java).apply { action = actionName },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val toggleLabel = if (paused) "Play" else "Pauze"

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(currentTitle)
            .setContentText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Terug", serviceAction(1, ACTION_PREVIOUS))
            .addAction(0, toggleLabel, serviceAction(2, ACTION_TOGGLE))
            .addAction(0, "Volgende", serviceAction(3, ACTION_NEXT))
            .addAction(0, "Stop", serviceAction(4, ACTION_STOP))
            .build()
    }

    private fun updateNotification(state: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(state))
    }

    private fun saveState(active: Boolean, playing: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .putBoolean(KEY_PLAYING, playing)
            .putString(KEY_TITLE, currentTitle)
            .putString(KEY_SOURCE, currentSource)
            .apply()
    }

    private fun saveSession(
        explicitPosition: Int? = null,
        explicitPlaying: Boolean? = null
    ) {
        if (urls.isEmpty() || index !in urls.indices) return

        val currentPosition = explicitPosition ?: playbackPositionMs()

        val currentlyPlaying = explicitPlaying ?: try {
            player?.isPlaying == true
        } catch (_: Exception) {
            false
        }

        val queueUrls = JSONArray()
        urls.forEach { queueUrls.put(it) }
        val queueTitles = JSONArray()
        titles.forEach { queueTitles.put(it) }

        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_SESSION, true)
            .putString(KEY_QUEUE_URLS, queueUrls.toString())
            .putString(KEY_QUEUE_TITLES, queueTitles.toString())
            .putInt(KEY_INDEX, index)
            .putInt(KEY_POSITION, currentPosition.coerceAtLeast(0))
            .putBoolean(KEY_WAS_PLAYING, currentlyPlaying)
            .putString(KEY_SOURCE, currentSource)
            .putString(KEY_TITLE, currentTitle)
            .apply()
    }

    private fun restoreLastSession() {
        if (urls.isNotEmpty() || player != null) return

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_SESSION, false)) return

        val restoredUrls = decodeStringArray(prefs.getString(KEY_QUEUE_URLS, null))
        if (restoredUrls.isEmpty()) {
            clearSession()
            return
        }

        val restoredTitles = decodeStringArray(prefs.getString(KEY_QUEUE_TITLES, null))
        urls = ArrayList(restoredUrls)
        titles = ArrayList(
            restoredTitles.takeIf { it.size == restoredUrls.size }
                ?: restoredUrls.map { "Muziek" }
        )
        index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, urls.lastIndex)
        currentSource = prefs.getString(KEY_SOURCE, "The One Mixes") ?: "The One Mixes"
        requestedStartPositionMs = prefs.getInt(KEY_POSITION, 0).coerceAtLeast(0)
        // Herstel altijd gepauzeerd. De gebruiker bepaalt zelf wanneer muziek
        // na het opnieuw openen van The One verder speelt.
        requestedAutoStart = false
        currentTitle = titles.getOrNull(index).orEmpty().ifBlank { "Muziek" }
        startCurrent()
    }

    private fun decodeStringArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(array.optString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clearSession() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HAS_SESSION)
            .remove(KEY_QUEUE_URLS)
            .remove(KEY_QUEUE_TITLES)
            .remove(KEY_INDEX)
            .remove(KEY_POSITION)
            .remove(KEY_WAS_PLAYING)
            .apply()
    }

    private fun stopPlayer() {
        // Ontkoppel eerst. Eventuele callbacks tijdens stop/release mogen de
        // zojuist gewiste sessie niet opnieuw opslaan.
        val oldPlayer = player
        player = null
        oldPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        if (urls.isNotEmpty() && index in urls.indices) {
            saveSession()
        }
        stateHandler.removeCallbacks(saveTick)
        stopPlayer()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "com.gmailorg.hub.SUPREMACY_PLAY"
        const val ACTION_STOP = "com.gmailorg.hub.SUPREMACY_STOP"
        const val ACTION_TOGGLE = "com.gmailorg.hub.SUPREMACY_TOGGLE"
        const val ACTION_NEXT = "com.gmailorg.hub.SUPREMACY_NEXT"
        const val ACTION_PREVIOUS = "com.gmailorg.hub.SUPREMACY_PREVIOUS"
        const val ACTION_SEEK = "com.gmailorg.hub.SUPREMACY_SEEK"
        const val ACTION_RESTORE_LAST = "com.gmailorg.hub.SUPREMACY_RESTORE_LAST"

        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        const val EXTRA_QUEUE_TITLES = "queue_titles"
        const val EXTRA_QUEUE_URLS = "queue_urls"
        const val EXTRA_INDEX = "queue_index"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_POSITION = "position_ms"

        private const val CHANNEL = "supremacy_mixes"
        private const val NOTIFICATION_ID = 2407
        private const val PREFS = "supremacy_playback"
        private const val KEY_ACTIVE = "active"
        private const val KEY_PLAYING = "playing"
        private const val KEY_TITLE = "title"
        private const val KEY_SOURCE = "source"
        private const val KEY_HAS_SESSION = "has_session"
        private const val KEY_QUEUE_URLS = "queue_urls"
        private const val KEY_QUEUE_TITLES = "queue_titles"
        private const val KEY_INDEX = "queue_index"
        private const val KEY_POSITION = "position_ms"
        private const val KEY_WAS_PLAYING = "was_playing"

        @Volatile private var instance: SupremacyPlaybackService? = null
        @Volatile private var pendingQueueUrls: List<String>? = null
        @Volatile private var pendingQueueTitles: List<String>? = null
        @Volatile private var pendingQueueSource: String? = null

        fun resumeLastSessionIfNeeded(context: Context) {
            if (instance != null) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_HAS_SESSION, false)) return

            val app = context.applicationContext
            val intent = Intent(app, SupremacyPlaybackService::class.java).apply {
                action = ACTION_RESTORE_LAST
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        fun isActive(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return instance?.urls?.isNotEmpty() == true ||
                prefs.getBoolean(KEY_ACTIVE, false)
        }

        fun isPlaying(context: Context): Boolean {
            val live = instance
            if (live?.player != null) {
                return try {
                    live.player?.isPlaying == true
                } catch (_: Exception) {
                    false
                }
            }
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLAYING, false)
        }

        fun currentTitle(context: Context): String {
            val live = instance
            if (live != null && live.urls.isNotEmpty()) {
                return live.currentTitle.takeIf { it.isNotBlank() }
                    ?: "Geen muziek actief"
            }
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TITLE, "Geen muziek actief")
                ?: "Geen muziek actief"
        }

        fun currentSource(context: Context): String {
            val live = instance
            if (live != null && live.urls.isNotEmpty()) {
                return live.currentSource.takeIf { it.isNotBlank() }
                    ?: "The One Mixes"
            }
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SOURCE, "The One Mixes")
                ?: "The One Mixes"
        }

        fun playQueue(
            context: Context,
            queueUrls: List<String>,
            queueTitles: List<String>,
            startIndex: Int,
            source: String
        ) {
            if (queueUrls.isEmpty()) return

            val titlesSafe =
                queueTitles.takeIf { it.size == queueUrls.size }
                    ?: queueUrls.map { "Muziek" }
            val safeIndex = startIndex.coerceIn(0, queueUrls.lastIndex)
            val selectedTitle =
                titlesSafe.getOrNull(safeIndex)
                    .orEmpty()
                    .ifBlank { "Muziek" }
            val sourceSafe = source.ifBlank { "The One Mixes" }

            pendingQueueUrls = queueUrls.toList()
            pendingQueueTitles = titlesSafe.toList()
            pendingQueueSource = sourceSafe

            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, true)
                .putBoolean(KEY_PLAYING, false)
                .putString(KEY_TITLE, selectedTitle)
                .putString(KEY_SOURCE, sourceSafe)
                .apply()

            val app = context.applicationContext
            val intent =
                Intent(app, SupremacyPlaybackService::class.java).apply {
                    action = ACTION_PLAY
                    putExtra(EXTRA_INDEX, safeIndex)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        fun currentUrl(context: Context): String {
            val live = instance
            if (live != null && live.urls.isNotEmpty() && live.index in live.urls.indices) {
                return live.urls[live.index]
            }

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE_URLS, null).orEmpty()
            if (raw.isBlank()) return ""
            return try {
                val array = JSONArray(raw)
                val index = prefs.getInt(KEY_INDEX, 0)
                    .coerceIn(0, (array.length() - 1).coerceAtLeast(0))
                if (array.length() == 0) "" else array.optString(index)
            } catch (_: Exception) {
                ""
            }
        }

        fun playBroadcast(
            context: Context,
            url: String,
            title: String,
            source: String,
            positionMs: Int
        ) {
            if (url.isBlank()) return

            val app = context.applicationContext
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, true)
                .putBoolean(KEY_PLAYING, false)
                .putString(KEY_TITLE, title.ifBlank { "Muziek" })
                .putString(KEY_SOURCE, source.ifBlank { "The One Broadcast" })
                .apply()

            val intent = Intent(app, SupremacyPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title.ifBlank { "Muziek" })
                putExtra(EXTRA_SOURCE, source.ifBlank { "The One Broadcast" })
                putExtra(EXTRA_POSITION, positionMs.coerceAtLeast(0))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        fun currentPositionMs(context: Context): Int =
            instance?.playbackPositionMs()
                ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_POSITION, 0)
                    .coerceAtLeast(0)

        fun currentDurationMs(): Int =
            instance?.playbackDurationMs() ?: 0

        fun currentQueueIndex(context: Context): Int =
            instance?.index
                ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_INDEX, 0)
                    .coerceAtLeast(0)

        fun seek(context: Context, positionMs: Int) {
            val active = instance
            if (active != null) {
                active.seekToPosition(positionMs)
                return
            }

            val app = context.applicationContext
            val intent = Intent(app, SupremacyPlaybackService::class.java).apply {
                action = ACTION_SEEK
                putExtra(EXTRA_POSITION, positionMs.coerceAtLeast(0))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }
    }
}
