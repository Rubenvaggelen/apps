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
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_BASE_ID = "supermarket_reminders"
        private const val NOTIFICATION_ID = 9001
        private const val PARKING_CHANNEL_BASE_ID = "parking_reminders"
        private const val PARKING_NOTIFICATION_ID = 9002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val triggeringIds = event.triggeringGeofences?.map { it.requestId } ?: emptyList()
        val isSupermarketTransition = triggeringIds.any { it.startsWith("supermarkt_") }

        // Widget bijwerken: lijst zichtbaar bij aankomst, verbergen bij vertrek.
        if (isSupermarketTransition) {
            when (event.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> ShoppingListWidgetProvider.setNearSupermarket(context, true)
                Geofence.GEOFENCE_TRANSITION_EXIT -> ShoppingListWidgetProvider.setNearSupermarket(context, false)
            }
        }

        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        if (isSupermarketTransition) {
            ShoppingListStore.init(context.applicationContext)
            val pending = ShoppingListStore.getAll().filter { !it.done }
            if (pending.isNotEmpty()) {
                val items = pending.map { it.text }
                showSupermarketNotification(context, items)
                if (CarRadioConnectionService.isRadioConnected()) {
                    CarRadioConnectionService.sendSupermarketAlert(items)
                }
            }
        }

        val triggeredParkingIds = triggeringIds.mapNotNull { ParkingGeofenceManager.addressIdFromRequestId(it) }
        if (triggeredParkingIds.isNotEmpty()) {
            ParkingAddressStore.init(context.applicationContext)
            val addressTexts = triggeredParkingIds.mapNotNull { ParkingAddressStore.get(it)?.address }
            if (addressTexts.isNotEmpty()) {
                showParkingNotification(context, addressTexts)
            }
        }
    }

    /**
     * Maakt (indien nodig) een notificatiekanaal aan met het door de gebruiker
     * gekozen meldingsgeluid. Een Android-kanaal kan zijn geluid niet meer
     * wijzigen na aanmaak, dus we hangen het geluids-versienummer achter de
     * kanaal-ID: zodra de gebruiker een ander geluid kiest, ontstaat er
     * automatisch een vers kanaal met dat nieuwe geluid.
     */
    private fun ensureChannel(context: Context, manager: NotificationManager, baseId: String, name: String, description: String): String {
        val version = NotificationSoundStore.getVersion(context)
        val channelId = "${baseId}_v$version"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH).apply {
                this.description = description
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(NotificationSoundStore.effectiveUri(context), audioAttributes)
            }
            manager.createNotificationChannel(channel)
        }
        return channelId
    }

    private fun showParkingNotification(context: Context, addresses: List<String>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ensureChannel(
            context, manager, PARKING_CHANNEL_BASE_ID,
            "Parkeer-herinneringen",
            "Melding als je aankomt bij een opgeslagen parkeeradres om te betalen voor parkeren."
        )

        val openIntent = Intent(context, ParkingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            context, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val addressText = if (addresses.size == 1) {
            addresses.first()
        } else {
            addresses.joinToString(", ")
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_tile_household)
            .setContentTitle("Parkeren 🅿️")
            .setContentText("Vergeet niet te betalen voor parkeren bij $addressText!")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Vergeet niet te betalen voor parkeren bij $addressText!"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(PARKING_NOTIFICATION_ID, notification)
    }

    private fun showSupermarketNotification(context: Context, items: List<String>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ensureChannel(
            context, manager, CHANNEL_BASE_ID,
            "Supermarkt-herinneringen",
            "Melding als je bij een supermarkt bent en nog iets op je boodschappenlijst staat."
        )

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

        val notification = NotificationCompat.Builder(context, channelId)
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
