package com.gmailorg.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Toont een melding zodra de door de gebruiker ingestelde parkeer-eindtijd
 * (op het Parkeren-scherm) is bereikt.
 */
class ParkingTimerReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_BASE_ID = "parking_timer"
        private const val NOTIFICATION_ID = 9003
    }

    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val version = NotificationSoundStore.getVersion(context)
        val channelId = "${CHANNEL_BASE_ID}_v$version"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Parkeer-eindtijd", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Melding als de door jou ingestelde parkeer-eindtijd is bereikt."
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(NotificationSoundStore.effectiveUri(context), audioAttributes)
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, ParkingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            context, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_tile_parking)
            .setContentTitle("Parkeertijd verlopen \u23f0")
            .setContentText("Je ingestelde parkeer-eindtijd is bereikt.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)

        // Eenmalige melding: eindtijd wissen zodat 'ie niet blijft staan.
        ParkingTimerStore.clear(context)
    }
}
