package com.gmailorg.carradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Achtergrondspeler voor USB-muziek.
 *
 * De MediaPlayer leeft in deze foreground service in plaats van in UsbMusicActivity.
 * Daardoor blijft muziek spelen wanneer de gebruiker teruggaat naar The One Car,
 * Route/WhatsApp opent of naar een andere app op de head-unit schakelt.
 *
 * De afspeelsessie wordt bovendien lokaal opgeslagen. Als de auto/head-unit uitgaat
 * terwijl muziek speelt, herstelt The One Car na de volgende start hetzelfde nummer
 * en ongeveer dezelfde afspeelpositie en speelt automatisch verder.
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
        private const val ACTION_RESTORE_LAST = "com.gmailorg.carradio.USB_RESTORE_LAST"

        private const val EXTRA_INDEX = "index"
        private const val EXTRA_POSITION = "position"

        private const val PREFS = "the_one_usb_playback_state"
        private const val KEY_HAS_SESSION = "has_session"
        private const val KEY_QUEUE = "queue_json"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position_ms"
        private const val KEY_WAS_PLAYING = "was_playing"
        private const val KEY_TITLE = "title"
        private const val KEY_URI = "uri"
        private const val KEY_SAVED_AT = "saved_at"

        @Volatile private var pendingQueue: List<QueueItem> = emptyList()
        @Volatile private var instance: UsbPlaybackService? = null
        @Volatile private var lastState = PlaybackState()

        const val DUCK_REASON_RECORDING = "whatsapp_recording"
        const val DUCK_REASON_CHAT_REQUEST = "whatsapp_voice_request"
        const val DUCK_REASON_CHAT_PLAYBACK = "whatsapp_voice_chat_playback"
        const val DUCK_REASON_INCOMING_MEDIA = "whatsapp_voice_incoming_media"

        private val duckLock = Any()
        private val duckReasons = LinkedHashMap<String, Float>()

        /**
         * Verlaagt alleen het volume van The One's eigen USB-speler. Het Android
         * systeemvolume van de K2401 blijft onaangeraakt. Meerdere redenen kunnen
         * tegelijk actief zijn; het laagste gevraagde volume wint.
         */
        fun beginDucking(reason: String, volume: Float = 0.12f) {
            synchronized(duckLock) {
                duckReasons[reason] = volume.coerceIn(0.0f, 1.0f)
            }
            instance?.applyDuckingVolume()
        }

        fun endDucking(reason: String) {
            synchronized(duckLock) { duckReasons.remove(reason) }
            instance?.applyDuckingVolume()
        }

        private fun targetVolume(): Float = synchronized(duckLock) {
            duckReasons.values.minOrNull() ?: 1.0f
        }

        fun play(context: Context, queue: List<QueueItem>, index: Int) {
            if (queue.isEmpty()) return
            pendingQueue = queue.toList()
            val safeIndex = index.coerceIn(0, queue.lastIndex)
            start(context, Intent(context, UsbPlaybackService::class.java).apply {
                action = ACTION_PLAY_INDEX
                putExtra(EXTRA_INDEX, safeIndex)
            })
        }

        fun toggle(context: Context) {
            val active = instance
            if (active != null) {
                active.toggleInternal()
            } else {
                start(
                    context,
                    Intent(context, UsbPlaybackService::class.java).apply {
                        action = ACTION_TOGGLE
                    }
                )
            }
        }

        fun next(context: Context) {
            val active = instance
            if (active != null) {
                active.playRelative(+1)
            } else {
                start(
                    context,
                    Intent(context, UsbPlaybackService::class.java).apply {
                        action = ACTION_NEXT
                    }
                )
            }
        }

        fun previous(context: Context) {
            val active = instance
            if (active != null) {
                active.playRelative(-1)
            } else {
                start(
                    context,
                    Intent(context, UsbPlaybackService::class.java).apply {
                        action = ACTION_PREVIOUS
                    }
                )
            }
        }

        fun seek(context: Context, positionMs: Int) {
            val active = instance
            if (active != null) {
                active.seekInternal(positionMs.coerceAtLeast(0))
            } else {
                start(
                    context,
                    Intent(context, UsbPlaybackService::class.java).apply {
                        action = ACTION_SEEK
                        putExtra(EXTRA_POSITION, positionMs.coerceAtLeast(0))
                    }
                )
            }
        }

        fun stop(context: Context) {
            val active = instance
            if (active != null) {
                active.stopPlaybackAndService()
            } else {
                start(
                    context,
                    Intent(context, UsbPlaybackService::class.java).apply {
                        action = ACTION_STOP
                    }
                )
            }
        }

        /**
         * Start de speler opnieuw na een reboot/app-herstart als er een opgeslagen sessie is.
         * Was de muziek vóór het uitzetten actief, dan speelt hij automatisch verder.
         * Was hij gepauzeerd, dan wordt alleen het nummer/positie hersteld.
         */
        fun resumeLastSessionIfNeeded(context: Context) {
            if (instance != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_HAS_SESSION, false)) return
            start(context, Intent(context, UsbPlaybackService::class.java).apply { action = ACTION_RESTORE_LAST })
        }

        fun snapshot(): PlaybackState = instance?.snapshotInternal() ?: lastState

        private fun start(context: Context, intent: Intent) {
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
            else app.startService(intent)
        }
    }

    private var player: ExoPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var queue: List<QueueItem> = emptyList()
    private var index = -1
    @Volatile private var preparing = false
    private var requestedStartPositionMs = 0
    private var requestedAutoStart = true
    private var restoring = false
    private val stateHandler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            if (queue.isNotEmpty()) persistSession()
            stateHandler.postDelayed(this, 2500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        configureMediaSession()
        stateHandler.post(saveTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android eist dat een via startForegroundService gestarte service snel foreground wordt.
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_PLAY_INDEX -> {
                val replacement = pendingQueue
                if (replacement.isNotEmpty()) queue = replacement
                if (queue.isNotEmpty()) playIndex(intent.getIntExtra(EXTRA_INDEX, 0), 0, true, false)
            }
            ACTION_TOGGLE -> toggleInternal()
            ACTION_NEXT -> playRelative(+1)
            ACTION_PREVIOUS -> playRelative(-1)
            ACTION_SEEK -> seekInternal(intent.getIntExtra(EXTRA_POSITION, 0))
            ACTION_STOP -> stopPlaybackAndService()
            ACTION_RESTORE_LAST -> restoreLastSession()
            null -> {
                // START_STICKY kan een service na procesherstart zonder intent terugbrengen.
                if (queue.isEmpty()) restoreLastSession()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun playIndex(
        requestedIndex: Int,
        startPositionMs: Int = 0,
        autoStart: Boolean = true,
        fromRestore: Boolean = false
    ) {
        if (queue.isEmpty()) return

        val newIndex = requestedIndex.coerceIn(0, queue.lastIndex)
        index = newIndex
        preparing = true
        requestedStartPositionMs = startPositionMs.coerceAtLeast(0)
        requestedAutoStart = autoStart
        restoring = fromRestore

        releasePlayer()

        lastState = PlaybackState(
            hasTrack = true,
            title = queue[index].title,
            uri = queue[index].uri,
            positionMs = requestedStartPositionMs,
            isPlaying = false,
            isPreparing = true
        )
        persistSession(
            explicitPosition = requestedStartPositionMs,
            explicitPlaying = autoStart
        )
        updateNotification()

        val exo = ExoPlayer.Builder(this).build()
        player = exo

        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )

        // Eén vaste ExoPlayer-queue: volgende nummers worden vooraf voorbereid
        // zodat er geen harde stop meer zit tussen twee tracks.
        exo.setMediaItems(queue.map { MediaItem.fromUri(it.uri) })
        exo.seekTo(index, requestedStartPositionMs.toLong())
        exo.playWhenReady = autoStart

        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player !== exo) return
                index = exo.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
                requestedStartPositionMs = 0
                preparing = false
                restoring = false
                updateStateCache()
                persistSession()
                updateNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== exo) return

                when (playbackState) {
                    Player.STATE_READY -> {
                        preparing = false
                        restoring = false
                        applyDuckingVolume()
                        updateStateCache()
                        persistSession()
                        updateNotification()
                    }

                    Player.STATE_ENDED -> {
                        preparing = false
                        updateStateCache()
                        persistSession()
                        updateNotification()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== exo) return
                updateStateCache()
                persistSession()
                updateNotification()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exo) return
                preparing = false
                updateStateCache()
                updateNotification()

                if (restoring) {
                    restoring = false
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        })

        try {
            exo.prepare()
        } catch (_: Exception) {
            preparing = false
            releasePlayer()
            updateStateCache()
            updateNotification()
            if (fromRestore) {
                restoring = false
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun restoreLastSession() {
        if (queue.isNotEmpty() || player != null) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_SESSION, false)) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val restoredQueue = decodeQueue(prefs.getString(KEY_QUEUE, null))
        if (restoredQueue.isEmpty()) {
            clearPersistedSession()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        queue = restoredQueue
        val restoredIndex = prefs.getInt(KEY_INDEX, 0).coerceIn(0, queue.lastIndex)
        val restoredPosition = prefs.getInt(KEY_POSITION, 0).coerceAtLeast(0)
        val shouldPlay = prefs.getBoolean(KEY_WAS_PLAYING, true)
        playIndex(restoredIndex, restoredPosition, shouldPlay, true)
    }

    private fun toggleInternal() {
        val exo = player
        if (exo == null) {
            if (queue.isNotEmpty()) {
                playIndex(if (index in queue.indices) index else 0)
            } else {
                restoreLastSession()
            }
            return
        }

        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
        }
        updateStateCache()
        persistSession()
        updateNotification()
    }

    private fun playRelative(delta: Int) {
        val exo = player
        if (exo == null) {
            if (queue.isNotEmpty()) playIndex((if (index in queue.indices) index else 0), 0, true, false)
            return
        }

        if (delta > 0) {
            if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
            else exo.seekTo(0, 0L)
        } else {
            if (exo.currentPosition > 3000L) {
                exo.seekTo(0L)
            } else if (exo.hasPreviousMediaItem()) {
                exo.seekToPreviousMediaItem()
            } else {
                exo.seekTo(0, 0L)
            }
        }
        exo.play()
    }

    private fun seekInternal(positionMs: Int) {
        val exo = player ?: return
        val duration = exo.duration
        val safePosition = if (duration > 0 && duration != C.TIME_UNSET) {
            positionMs.toLong().coerceAtMost(duration)
        } else {
            positionMs.toLong()
        }
        exo.seekTo(safePosition.coerceAtLeast(0L))
        updateStateCache()
        persistSession()
    }

    private fun stopPlaybackAndService() {
        releasePlayer()
        queue = emptyList()
        index = -1
        preparing = false
        lastState = PlaybackState()
        clearPersistedSession()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }

    private fun applyDuckingVolume() {
        val volume = targetVolume()
        try { player?.volume = volume } catch (_: Exception) {}
    }

    private fun snapshotInternal(): PlaybackState {
        val item = queue.getOrNull(index)
        val exo = player
        var duration = 0
        var position = requestedStartPositionMs.coerceAtLeast(0)
        var playing = false

        if (exo != null) {
            val rawDuration = exo.duration
            if (rawDuration > 0 && rawDuration != C.TIME_UNSET) {
                duration = rawDuration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            position = exo.currentPosition
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            playing = exo.isPlaying
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

    private fun persistSession(explicitPosition: Int? = null, explicitPlaying: Boolean? = null) {
        if (queue.isEmpty() || index !in queue.indices) return
        val state = snapshotInternal()
        val pos = explicitPosition ?: state.positionMs
        val playing = explicitPlaying ?: state.isPlaying
        val item = queue[index]
        val array = JSONArray()
        queue.forEach { q ->
            array.put(JSONObject().apply {
                put("uri", q.uri)
                put("title", q.title)
            })
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS_SESSION, true)
            .putString(KEY_QUEUE, array.toString())
            .putInt(KEY_INDEX, index)
            .putInt(KEY_POSITION, pos.coerceAtLeast(0))
            .putBoolean(KEY_WAS_PLAYING, playing)
            .putString(KEY_TITLE, item.title)
            .putString(KEY_URI, item.uri)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun decodeQueue(raw: String?): List<QueueItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val uri = obj.optString("uri").trim()
                    if (uri.isBlank()) continue
                    add(QueueItem(uri, obj.optString("title", "Nummer")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clearPersistedSession() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun configureMediaSession() {
        mediaSession = MediaSessionCompat(this, "TheOneCarAudio").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    val state = snapshotInternal()
                    if (!state.isPlaying) toggleInternal()
                }

                override fun onPause() {
                    val state = snapshotInternal()
                    if (state.isPlaying) toggleInternal()
                }

                override fun onStop() {
                    stopPlaybackAndService()
                }

                override fun onSkipToNext() {
                    playRelative(+1)
                }

                override fun onSkipToPrevious() {
                    playRelative(-1)
                }

                override fun onSeekTo(pos: Long) {
                    seekInternal(
                        pos.coerceAtLeast(0L)
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                    )
                }
            })
            isActive = true
        }
        updateMediaSession()
    }

    private fun updateMediaSession() {
        if (!::mediaSession.isInitialized) return

        val state = snapshotInternal()
        val playbackState = when {
            state.isPreparing -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            state.hasTrack -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    playbackState,
                    state.positionMs.toLong(),
                    if (state.isPlaying) 1f else 0f
                )
                .build()
        )

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    state.title
                )
                .putLong(
                    MediaMetadataCompat.METADATA_KEY_DURATION,
                    state.durationMs.toLong()
                )
                .build()
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "The One Muziek",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Achtergrondweergave van Shared Media en The One Mixes in The One Car"
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        updateMediaSession()
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
        if (queue.isNotEmpty()) persistSession()
        stateHandler.removeCallbacks(saveTick)
        releasePlayer()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        if (instance === this) instance = null
        super.onDestroy()
    }
}
