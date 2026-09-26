package com.gmailorg.carradio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SupremacyPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private var currentTitle = "Supremacy mixen"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(
                NotificationChannel(CHANNEL, "Supremacy mixen", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPlayer(); stopSelf() }
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                currentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Supremacy mixen" }
                if (url.isNotBlank()) startPlayback(url)
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(url: String) {
        stopPlayer()
        startForeground(NOTIFICATION_ID, notification("Laden…"))
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
            setDataSource(url)
            setOnPreparedListener { it.start(); updateNotification("Speelt af") }
            setOnCompletionListener { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            setOnErrorListener { _, _, _ -> stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); true }
            prepareAsync()
        }
    }

    private fun notification(state: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SupremacyMixesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SupremacyPlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_home_music_fancy)
            .setContentTitle(currentTitle)
            .setContentText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(state: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
    }

    private fun stopPlayer() { try { player?.stop() } catch (_: Exception) {}; player?.release(); player = null }
    override fun onDestroy() { stopPlayer(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PLAY = "com.gmailorg.carradio.SUPREMACY_PLAY"
        const val ACTION_STOP = "com.gmailorg.carradio.SUPREMACY_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        private const val CHANNEL = "supremacy_mixes"
        private const val NOTIFICATION_ID = 2406
    }
}
