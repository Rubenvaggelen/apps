package com.gmailorg.carradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Achtergrondspeler voor USB-muziek.
 *
 * De MediaPlayer leeft in deze foreground service in plaats van in UsbMusicActivity.
 * Daardoor blijft muziek spelen wanneer de gebruiker teruggaat naar The One Car,
 * Route/WhatsApp opent of naar een andere app op de head-unit schakelt.
 */
class UsbPlaybackService : Service() {

    data class QueueItem(val uri: String, val title: String)
    data class PlaybackState(
        val hasTrack: Boolean = false,
        val title: String = "Geen nummer geselecteerd",
        val uri: String? = null,
        val durationMs: Int = 0,
        val positionMs: Int = 0,
        val isPlaying: Boolean = false,
        val isPreparing: Boolean = false
    )

    companion object {
        private const val CHANNEL_ID = "the_one_usb_playback"
        private const val NOTIFICATION_ID = 4102

        private const val ACTION_PLAY_INDEX = "com.gmailorg.carradio.USB_PLAY_INDEX"
        private const val ACTION_TOGGLE = "com.gmailorg.carradio.USB_TOGGLE"
        private const val ACTION_NEXT = "com.gmailorg.carradio.USB_NEXT"
        private const val ACTION_PREVIOUS = "com.gmailorg.carradio.USB_PREVIOUS"
        private const val ACTION_SEEK = "com.gmailorg.carradio.USB_SEEK"
        private const val ACTION_STOP = "com.gmailorg.carradio.USB_STOP"

        private const val EXTRA_INDEX = "index"
        private const val EXTRA_POSITION = "position"

        @Volatile private var pendingQueue: List<QueueItem> = emptyList()
        @Volatile private var instance: UsbPlaybackService? = null
        @Volatile private var lastState = PlaybackState()

        fun play(context: Context, queue: List<QueueItem>, index: Int) {
            if (queue.isEmpty()) return
            pendingQueue = queue.toList()
            val safeIndex = index.coerceIn(0, queue.lastIndex)
            start(context, Intent(context, UsbPlaybackService::class.java).apply {
                action = ACTION_PLAY_INDEX
                putExtra(EXTRA_INDEX, safeIndex)
            })
        }

        fun toggle(context: Context) = start(context, Intent(context, UsbPlaybackService::class.java).apply { action = ACTION_TOGGLE })
        fun next(context: Context) = start(context, Intent(context, UsbPlaybackService::class.java).apply { action = ACTION_NEXT })
        fun previous(context: Context) = start(context, Intent(context, UsbPlaybackService::class.java).apply { action = ACTION_PREVIOUS })
        fun seek(context: Context, positionMs: Int) = start(context, Intent(context, UsbPlaybackService::class.java).apply {
            action = ACTION_SEEK
            putExtra(EXTRA_POSITION, positionMs.coerceAtLeast(0))
        })
        fun stop(context: Context) = start(context, Intent(context, UsbPlaybackService::class.java).apply { action = ACTION_STOP })

        fun snapshot(): PlaybackState = instance?.snapshotInternal() ?: lastState

        private fun start(context: Context, intent: Intent) {
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
            else app.startService(intent)
        }
    }

    private var player: MediaPlayer? = null
    private var queue: List<QueueItem> = emptyList()
    private var index = -1
    @Volatile private var preparing = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android eist dat een via startForegroundService gestarte service snel foreground wordt.
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_PLAY_INDEX -> {
                val replacement = pendingQueue
                if (replacement.isNotEmpty()) queue = replacement
                if (queue.isNotEmpty()) playIndex(intent.getIntExtra(EXTRA_INDEX, 0))
            }
            ACTION_TOGGLE -> toggleInternal()
            ACTION_NEXT -> playRelative(+1)
            ACTION_PREVIOUS -> playRelative(-1)
            ACTION_SEEK -> seekInternal(intent.getIntExtra(EXTRA_POSITION, 0))
            ACTION_STOP -> stopPlaybackAndService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun playIndex(requestedIndex: Int) {
        if (queue.isEmpty()) return
        val newIndex = requestedIndex.coerceIn(0, queue.lastIndex)
        val item = queue[newIndex]
        index = newIndex
        preparing = true
        releasePlayer()
        lastState = PlaybackState(
            hasTrack = true,
            title = item.title,
            uri = item.uri,
            isPreparing = true
        )
        updateNotification()

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(this, Uri.parse(item.uri))
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                preparing = false
                try { it.start() } catch (_: Exception) {}
                updateStateCache()
                updateNotification()
            }
            mp.setOnCompletionListener {
                if (player === it) playRelative(+1)
            }
            mp.setOnErrorListener { badPlayer, _, _ ->
                if (player === badPlayer) {
                    preparing = false
                    updateStateCache()
                    updateNotification()
                }
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            preparing = false
            releasePlayer()
            updateStateCache()
            updateNotification()
        }
    }

    private fun toggleInternal() {
        val mp = player
        if (mp == null) {
            if (queue.isNotEmpty()) playIndex(if (index in queue.indices) index else 0)
            return
        }
        try {
            if (mp.isPlaying) mp.pause() else if (!preparing) mp.start()
        } catch (_: Exception) {}
        updateStateCache()
        updateNotification()
    }

    private fun playRelative(delta: Int) {
        if (queue.isEmpty()) return
        val base = if (index in queue.indices) index else 0
        val next = (base + delta + queue.size) % queue.size
        playIndex(next)
    }

    private fun seekInternal(positionMs: Int) {
        try {
            val mp = player ?: return
            if (!preparing) mp.seekTo(positionMs.coerceAtMost(mp.duration.coerceAtLeast(0)))
        } catch (_: Exception) {}
        updateStateCache()
    }

    private fun stopPlaybackAndService() {
        releasePlayer()
        queue = emptyList()
        index = -1
        preparing = false
        lastState = PlaybackState()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.reset() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }

    private fun snapshotInternal(): PlaybackState {
        val item = queue.getOrNull(index)
        val mp = player
        var duration = 0
        var position = 0
        var playing = false
        if (mp != null) {
            try {
                duration = if (preparing) 0 else mp.duration.coerceAtLeast(0)
                position = if (preparing) 0 else mp.currentPosition.coerceAtLeast(0)
                playing = !preparing && mp.isPlaying
            } catch (_: Exception) {}
        }
        val state = PlaybackState(
            hasTrack = item != null,
            title = item?.title ?: "Geen nummer geselecteerd",
            uri = item?.uri,
            durationMs = duration,
            positionMs = position,
            isPlaying = playing,
            isPreparing = preparing
        )
        lastState = state
        return state
    }

    private fun updateStateCache() {
        snapshotInternal()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "USB muziek",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Achtergrondweergave van USB-muziek in The One Car"
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun buildNotification(): android.app.Notification {
        val state = snapshotInternal()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, UsbMusicActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val previousIntent = PendingIntent.getService(
            this, 1, Intent(this, UsbPlaybackService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this, 2, Intent(this, UsbPlaybackService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, UsbPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title)
            .setContentText(if (state.isPreparing) "Laden…" else if (state.isPlaying) "The One Car • speelt af" else "The One Car • gepauzeerd")
            .setContentIntent(contentIntent)
            .setOngoing(state.hasTrack)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Vorige", previousIntent)
            .addAction(if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (state.isPlaying) "Pauze" else "Afspelen", toggleIntent)
            .addAction(android.R.drawable.ic_media_next, "Volgende", nextIntent)
            .build()
    }

    override fun onDestroy() {
        releasePlayer()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
