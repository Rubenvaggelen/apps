package com.gmailorg.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "supermarket_reminders"
        private const val NOTIFICATION_ID = 9001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        ShoppingListStore.init(context.applicationContext)
        val pending = ShoppingListStore.getAll().filter { !it.done }
        if (pending.isEmpty()) return // niets op de lijst, geen melding nodig

        showNotification(context, pending.map { it.text })
    }

    private fun showNotification(context: Context, items: List<String>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Supermarkt-herinneringen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Melding als je bij een supermarkt bent en nog iets op je boodschappenlijst staat."
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, HouseholdActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val itemsText = if (items.size <= 5) {
            items.joinToString(", ")
        } else {
            items.take(5).joinToString(", ") + " en ${items.size - 5} meer"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_household)
            .setContentTitle("Je bent bij een supermarkt 🛒")
            .setContentText("Vergeet niet: $itemsText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Vergeet niet: $itemsText"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
