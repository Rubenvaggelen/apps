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
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SupremacyPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var currentTitle = "Supremacy mixen"
    private var titles = arrayListOf<String>()
    private var urls = arrayListOf<String>()
    private var index = 0
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Supremacy mixen", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayer()
                saveState(active = false, playing = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_TOGGLE -> togglePlayback()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()

            ACTION_PLAY -> {
                val incomingUrls = intent.getStringArrayListExtra(EXTRA_QUEUE_URLS)
                val incomingTitles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES)
                val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

                if (!incomingUrls.isNullOrEmpty()) {
                    urls = ArrayList(incomingUrls)
                    titles = ArrayList(
                        incomingTitles?.takeIf { it.size == incomingUrls.size }
                            ?: incomingUrls.map { "Supremacy mixen" }
                    )
                    index = startIndex.coerceIn(0, urls.lastIndex)
                } else {
                    val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                    val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Supremacy mixen" }
                    if (url.isBlank()) return START_NOT_STICKY
                    urls = arrayListOf(url)
                    titles = arrayListOf(title)
                    index = 0
                }

                startCurrent()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCurrent() {
        if (urls.isEmpty() || index !in urls.indices) return
        currentTitle = titles.getOrNull(index).orEmpty().ifBlank { "Supremacy mixen" }
        startPlayback(urls[index])
    }

    private fun startPlayback(url: String) {
        stopPlayer()
        paused = false
        saveState(active = true, playing = false)
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
                it.start()
                paused = false
                saveState(active = true, playing = true)
                updateNotification("Speelt af")
            }
            setOnCompletionListener {
                if (index + 1 < urls.size) {
                    index++
                    startCurrent()
                } else {
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
            updateNotification("Gepauzeerd")
        } else {
            p.start()
            paused = false
            saveState(active = true, playing = true)
            updateNotification("Speelt af")
        }
    }

    private fun next() {
        if (urls.isEmpty()) return
        if (index + 1 < urls.size) {
            index++
            startCurrent()
        }
    }

    private fun previous() {
        if (urls.isEmpty()) return
        if (index > 0) {
            index--
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
            .apply()
    }

    private fun stopPlayer() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "com.gmailorg.hub.SUPREMACY_PLAY"
        const val ACTION_STOP = "com.gmailorg.hub.SUPREMACY_STOP"
        const val ACTION_TOGGLE = "com.gmailorg.hub.SUPREMACY_TOGGLE"
        const val ACTION_NEXT = "com.gmailorg.hub.SUPREMACY_NEXT"
        const val ACTION_PREVIOUS = "com.gmailorg.hub.SUPREMACY_PREVIOUS"

        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        const val EXTRA_QUEUE_TITLES = "queue_titles"
        const val EXTRA_QUEUE_URLS = "queue_urls"
        const val EXTRA_INDEX = "queue_index"

        private const val CHANNEL = "supremacy_mixes"
        private const val NOTIFICATION_ID = 2407
        private const val PREFS = "supremacy_playback"
        private const val KEY_ACTIVE = "active"
        private const val KEY_PLAYING = "playing"
        private const val KEY_TITLE = "title"

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
    }
}
