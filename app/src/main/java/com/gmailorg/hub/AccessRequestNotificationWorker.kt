package com.gmailorg.hub

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class AccessRequestNotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        if (!MainDeviceRegistry.isLocallyOwner(applicationContext)) {
            return Result.success()
        }

        val pending = runCatching {
            MainDeviceRegistry.pendingAccessRequests(applicationContext)
        }.getOrElse {
            return Result.retry()
        }

        AccessRequestNotifications.notifyNew(applicationContext, pending)
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "the-one-access-request-watch"
        private const val IMMEDIATE_WORK = "the-one-access-request-now"

        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<AccessRequestNotificationWorker>(
                15,
                TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodic
                )
        }

        fun checkNow(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    IMMEDIATE_WORK,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AccessRequestNotificationWorker>().build()
                )
        }
    }
}

object AccessRequestNotifications {
    private const val CHANNEL_ID = "the_one_access_requests"
    private const val PREFS = "access_request_notifications"
    private const val KEY_SEEN = "seen_keys"

    fun notifyNew(
        context: Context,
        requests: List<MainPendingAccessRequest>
    ) {
        if (!MainDeviceRegistry.isLocallyOwner(context)) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
        val activeKeys = requests.map { requestKey(it) }.toSet()

        requests.forEach { request ->
            val key = requestKey(request)
            if (key in seen) return@forEach

            val person = request.personName.ifBlank {
                request.deviceName.ifBlank { "Iemand" }
            }
            val scopeLabel = scopeLabel(request.scope)

            val intent = Intent(context, WakePcActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_access_management", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                key.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Toegangsverzoek van $person")
                .setContentText("$person vraagt toestemming voor $scopeLabel.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$person vraagt toestemming voor $scopeLabel. " +
                            "Tik om de rechten te bekijken en toe te staan of te weigeren."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(key.hashCode(), notification)
            seen += key
        }

        // Opgeloste aanvragen uit de dedupe-set verwijderen. Een nieuwe aanvraag
        // voor hetzelfde recht krijgt een nieuw requested_at en meldt dus opnieuw.
        seen.retainAll(activeKeys)
        prefs.edit().putStringSet(KEY_SEEN, seen).apply()
    }

    private fun requestKey(request: MainPendingAccessRequest): String =
        request.deviceId + "|" + request.scope + "|" + request.requestedAt

    private fun scopeLabel(scope: String): String = when (scope) {
        MainDeviceRegistry.ACCESS_MIXES -> "The One Mixes"
        MainDeviceRegistry.ACCESS_SHARED -> "Shared Media"
        MainDeviceRegistry.ACCESS_FAVORITES -> "Favorites"
        MainDeviceRegistry.ACCESS_DJ -> "The One DJ import"
        MainDeviceRegistry.ACCESS_ORGANIZE -> "muziek organiseren"
        MainDeviceRegistry.ACCESS_FILE_DOWNLOADS -> "bestanden downloaden"
        else -> scope
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Toegangsverzoeken",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Meldingen wanneer iemand The One-rechten aanvraagt."
        }
        manager.createNotificationChannel(channel)
    }
}
