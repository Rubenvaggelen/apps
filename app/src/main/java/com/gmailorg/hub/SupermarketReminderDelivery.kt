package com.gmailorg.hub

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** One delivery path, so location checks and ENTER/DWELL cannot double-notify. */
object SupermarketReminderDelivery {
    private const val PREFS = "supermarket_reminder_delivery"
    private const val LAST_DELIVERED = "last_delivered"

    fun channelId(context: Context) = "supermarket_reminders_v" + NotificationSoundStore.getVersion(context)

    fun blockedReason(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return "Sta meldingen voor Main toe."
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled())
            return "Android blokkeert meldingen van Main."
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId(context))?.importance == NotificationManager.IMPORTANCE_NONE)
            return "Het meldingskanaal Supermarkt-herinneringen is uitgezet."
        return null
    }

    @Synchronized
    fun deliver(context: Context, test: Boolean = false): String {
        val app = context.applicationContext
        ShoppingListStore.init(app)
        val items = ShoppingListStore.getAll().filter { !it.done }.map { it.text }
        val blocked = blockedReason(app)
        if (blocked != null) {
            app.getSharedPreferences("household_geofence_prefs", Context.MODE_PRIVATE)
                .edit().putString("last_error", blocked).apply()
            return blocked
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!test && !SupermarketReminderPolicy.mayDeliver(SupermarketGeofenceManager.isEnabled(app),
                items.size, true, prefs.getLong(LAST_DELIVERED, 0), now)) {
            return when {
                !SupermarketGeofenceManager.isEnabled(app) -> "Supermarktmeldingen staan uit."
                items.isEmpty() -> "Bij een supermarkt, maar je lijst bevat geen openstaande boodschappen."
                else -> "Bij een supermarkt. Er is de afgelopen 15 minuten al een boodschappenmelding geplaatst."
            }
        }
        val content = if (test && items.isEmpty()) listOf("Je boodschappenmeldingen kunnen worden getoond.") else items
        if (!GeofenceBroadcastReceiver().showSupermarketNotification(app, content, test))
            return "Android kon de boodschappenmelding niet plaatsen."
        if (!test) {
            prefs.edit().putLong(LAST_DELIVERED, now).apply()
        }
        return if (test) "Testmelding aangeboden. Controleer je meldingen." else "Boodschappenmelding geplaatst."
    }
}
