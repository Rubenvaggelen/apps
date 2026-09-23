package com.vanaggelen.jadeorders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.concurrent.TimeUnit

class WednesdayOrderReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        try {
            val name = applicationContext
                .getSharedPreferences("rutu_customer", Context.MODE_PRIVATE)
                .getString("name", "")
                .orEmpty()
                .trim()

            val url = URL(
                "https://rubenvanaggelen.com/rutu-api/index.php?action=announcement&customer=" +
                    URLEncoder.encode(name, "UTF-8")
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 9000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code == 200) {
                val announcement = JSONObject(raw).optJSONObject("announcement")
                if (announcement?.optBoolean("ordering_allowed", false) == true) {
                    showNotification()
                }
            }
        } catch (_: Exception) {
            // Een mislukte controle mag de app niet hinderen; volgende woensdag wordt opnieuw gepland.
        } finally {
            scheduleNext(applicationContext)
        }
        return Result.success()
    }

    private fun showNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Rutu bestelmomenten",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Melding wanneer klanten op woensdag kunnen bestellen bij Rutu BBQ"
                }
            )
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "Rutu BBQ is open voor bestellingen"
        val message = "Het is woensdag! Je kunt vandaag van 10:00 uur tot 18:00 uur je bestelling plaatsen bij Rutu BBQ."

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(applicationContext)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pending)

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    companion object {
        private const val UNIQUE_WORK = "rutu_wednesday_10_order_reminder"
        private const val CHANNEL_ID = "rutu_order_moments"
        private const val NOTIFICATION_ID = 14100
        private val AMSTERDAM = ZoneId.of("Europe/Amsterdam")

        fun schedule(context: Context) {
            enqueue(context, ExistingWorkPolicy.REPLACE)
        }

        private fun scheduleNext(context: Context) {
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val now = ZonedDateTime.now(AMSTERDAM)
            var target = now
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)

            if (!target.isAfter(now)) target = target.plusWeeks(1)

            val delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<WednesdayOrderReminderWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                policy,
                request
            )
        }
    }
}
