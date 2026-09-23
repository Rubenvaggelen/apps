package com.vanaggelen.jadeorders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class OrderStatusService : Service() {
    private fun bi(nl: String, en: String): String = "$nl\n$en"
    private fun statusEnglish(status: String): String = when (status) {
        "Nieuw" -> "New"
        "In bereiding" -> "Being prepared"
        "Klaar" -> "Ready"
        "Bestelling is onderweg" -> "Order is on the way"
        "Afgerond" -> "Completed"
        "Uitverkocht" -> "Sold out"
        "Geweigerd" -> "Order declined"
        "Geannuleerd" -> "Order cancelled"
        else -> status
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceChannel = "rutu_background_updates"
    private val statusChannel = "rutu_order_updates"
    private val finalStatuses = setOf("Afgerond", "Geannuleerd", "Uitverkocht", "Geweigerd")
    private val poller = Runnable { pollAsync() }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(13001, serviceNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(poller)
        handler.post(poller)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollAsync() {
        Thread {
            val keepRunning = pollOnce()
            if (keepRunning) handler.postDelayed(poller, 30_000L)
            else stopSelf()
        }.start()
    }

    private fun pollOnce(): Boolean {
        val tracked = MainActivity.Store.all(this)
            .filter { it.trackingToken.isNotBlank() && it.status !in finalStatuses }
        if (tracked.isEmpty()) return false

        tracked.forEach { order ->
            try {
                val url = URL(
                    "https://rubenvanaggelen.com/rutu-api/index.php?action=status&id=" +
                        URLEncoder.encode(order.id.toString(), "UTF-8") +
                        "&tracking=" + URLEncoder.encode(order.trackingToken, "UTF-8")
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 9000
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()

                if (code == 200) {
                    val status = JSONObject(raw).optJSONObject("order")?.optString("status").orEmpty()
                    if (status.isNotBlank() && status != order.status) {
                        if (status == "Afgerond") {
                            getSharedPreferences("rutu_customer_banner", Context.MODE_PRIVATE)
                                .edit()
                                .putLong("eat_well_until", System.currentTimeMillis() + 3 * 60 * 1000L)
                                .putBoolean("force_landing", true)
                                .apply()
                        }
                        notifyStatus(order.id, status)
                        MainActivity.Store.status(this, order.id, status)
                    }
                }
            } catch (_: Exception) {
                // Keep the service alive and retry on the next poll.
            }
        }

        return MainActivity.Store.all(this)
            .any { it.trackingToken.isNotBlank() && it.status !in finalStatuses }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                serviceChannel,
                "Rutu bestelupdates actief / Rutu order updates active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Houdt openstaande bestellingen op de achtergrond bij / Tracks open orders in the background"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                statusChannel,
                "Bestelupdates / Order updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Belangrijke updates over je Rutu BBQ-bestelling / Important updates about your Rutu BBQ order"
            }
        )
    }

    private fun serviceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            13001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, serviceChannel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Rutu bestelupdates actief / Rutu order updates active")
            .setContentText(bi("Statuswijzigingen komen automatisch op je telefoon.", "Status changes will automatically appear on your phone."))
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun notifyStatus(orderId: Int, status: String) {
        val prefs = getSharedPreferences("rutu_notification_state", Context.MODE_PRIVATE)
        val key = "order_$orderId"
        if (prefs.getString(key, "") == status) return
        prefs.edit().putString(key, status).apply()

        val title = when (status) {
            "In bereiding" -> "Je bestelling wordt bereid / Your order is being prepared"
            "Klaar" -> "Je bestelling is klaar / Your order is ready"
            "Bestelling is onderweg" -> "Uw bestelling is onderweg / Your order is on the way"
            "Afgerond" -> "Bestelling afgegeven / Order delivered"
            "Uitverkocht" -> "Uitverkocht / Sold out"
            "Geweigerd" -> "Bestelling geweigerd / Order declined"
            "Geannuleerd" -> "Bestelling geannuleerd / Order cancelled"
            else -> "Bestelupdate / Order update"
        }
        val message = when (status) {
            "Uitverkocht" -> bi("Bestelling #$orderId is helaas uitverkocht.", "Order #$orderId is unfortunately sold out.")
            "Bestelling is onderweg" -> bi("Uw bestelling is onderweg.", "Your order is on the way.")
            "Afgerond" -> bi("Uw bestelling is afgegeven. Eet u smakelijk.", "Your order has been delivered. Enjoy your meal.")
            else -> bi("Bestelling #$orderId heeft nu status: $status.", "Order #$orderId now has status: ${statusEnglish(status)}.")
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            orderId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, statusChannel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pending)

        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(12000 + orderId, builder.build())
        }
    }
}
