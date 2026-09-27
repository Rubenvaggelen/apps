package com.gmailorg.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

class SupremacyPlaybackService : Service() {
    private var player: MediaPlayer? = null
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
                stopPlayer()
                clearSession()
                saveState(active = false, playing = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_TOGGLE -> togglePlayback()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_RESTORE_LAST -> restoreLastSession()

            ACTION_PLAY -> {
                currentSource = intent.getStringExtra(EXTRA_SOURCE).orEmpty().ifBlank { "The One Mixes" }
                val incomingUrls = intent.getStringArrayListExtra(EXTRA_QUEUE_URLS)
                val incomingTitles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES)
                val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

                if (!incomingUrls.isNullOrEmpty()) {
                    urls = ArrayList(incomingUrls)
                    titles = ArrayList(
                        incomingTitles?.takeIf { it.size == incomingUrls.size }
                            ?: incomingUrls.map { "The One Mixes" }
                    )
                    index = startIndex.coerceIn(0, urls.lastIndex)
                } else {
                    val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                    val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "The One Mixes" }
                    if (url.isBlank()) return START_NOT_STICKY
                    urls = arrayListOf(url)
                    titles = arrayListOf(title)
                    index = 0
                }

                requestedStartPositionMs = 0
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
        saveSession(explicitPosition = requestedStartPositionMs, explicitPlaying = autoStart)
        startForeground(NOTIFICATION_ID, notification("Laden…"))

        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(url)
            setOnPreparedListener {
                val seekTo = requestedStartPositionMs.coerceAtMost(it.duration.coerceAtLeast(0))
                if (seekTo > 0) {
                    try { it.seekTo(seekTo) } catch (_: Exception) {}
                }
                if (requestedAutoStart) {
                    it.start()
                    paused = false
                } else {
                    paused = true
                }
                saveState(active = true, playing = requestedAutoStart)
                saveSession()
                updateNotification(if (requestedAutoStart) "Speelt af" else "Gepauzeerd")
            }
            setOnCompletionListener {
                if (index + 1 < urls.size) {
                    index++
                    requestedStartPositionMs = 0
                    requestedAutoStart = true
                    startCurrent()
                } else {
                    clearSession()
                    saveState(active = false, playing = false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            setOnErrorListener { _, _, _ ->
                saveState(active = false, playing = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                true
            }
            prepareAsync()
        }
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            paused = true
            saveState(active = true, playing = false)
            saveSession()
            updateNotification("Gepauzeerd")
        } else {
            p.start()
            paused = false
            saveState(active = true, playing = true)
            saveSession()
            updateNotification("Speelt af")
        }
    }

    private fun next() {
        if (urls.isEmpty()) return
        if (index + 1 < urls.size) {
            index++
            requestedStartPositionMs = 0
            requestedAutoStart = true
            startCurrent()
        }
    }

    private fun previous() {
        if (urls.isEmpty()) return
        if (index > 0) {
            index--
            requestedStartPositionMs = 0
            requestedAutoStart = true
            startCurrent()
        } else {
            try { player?.seekTo(0) } catch (_: Exception) {}
        }
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

        val currentPosition = explicitPosition ?: try {
            player?.currentPosition ?: requestedStartPositionMs
        } catch (_: Exception) {
            requestedStartPositionMs
        }

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
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
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
        const val ACTION_RESTORE_LAST = "com.gmailorg.hub.SUPREMACY_RESTORE_LAST"

        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        const val EXTRA_QUEUE_TITLES = "queue_titles"
        const val EXTRA_QUEUE_URLS = "queue_urls"
        const val EXTRA_INDEX = "queue_index"
        const val EXTRA_SOURCE = "source"

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

        fun isActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false)

        fun isPlaying(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PLAYING, false)

        fun currentTitle(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TITLE, "Geen muziek actief")
                ?: "Geen muziek actief"

        fun currentSource(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SOURCE, "The One Mixes")
                ?: "The One Mixes"
    }
}
