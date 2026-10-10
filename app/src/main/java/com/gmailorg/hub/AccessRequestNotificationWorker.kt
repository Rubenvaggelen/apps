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

        return synchronized(AccessRequestNotifications.syncLock) {
        // Read license approvals independently of the existing media-rights
        // endpoint: a failure in one must not hide requests from the other.
        val accessResult = runCatching {
            MainDeviceRegistry.pendingAccessRequests(applicationContext)
        }
        accessResult.onSuccess { AccessRequestNotifications.notifyNew(applicationContext, it) }

        val ownerPaired = MainLicenseClient.ownerIsPaired(applicationContext)
        val licenseResult = if (ownerPaired) {
            runCatching {
                MainLicenseClient.pendingOwnerApprovals(applicationContext)
            }.onSuccess {
                AccessRequestNotifications.notifyPendingLicenses(applicationContext, it)
            }
        } else null

        if (accessResult.isFailure || licenseResult?.isFailure == true) {
            Result.retry()
        } else Result.success()
        }
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
    val syncLock = Any()

    fun resolve(context: Context, deviceId: String, scope: String) {
        NotifStore.init(context.applicationContext)
        val prefix = "theone-access|$deviceId|$scope|"
        NotifStore.removeWhere(includePersistent = true) { it.actionType == "access_request" && it.key.startsWith(prefix) }
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = preferences.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        val resolved = seen.filter { it.startsWith("$deviceId|$scope|") }.toSet()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        resolved.forEach { manager.cancel(("access|" + it).hashCode()) }
        preferences.edit().putStringSet(KEY_SEEN, seen - resolved).apply()
    }
    fun resolveLicense(context: Context, requestId: String) {
        if (!requestId.matches(Regex("^[a-f0-9]{24}$"))) return
        val key = "theone-license|" + requestId
        NotifStore.init(context.applicationContext)
        NotifStore.removeWhere(includePersistent = true) {
            it.actionType == "license_request" && it.key == key
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(("license|" + key).hashCode())
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = preferences.getStringSet(KEY_SEEN_LICENSES, emptySet()).orEmpty()
        preferences.edit().putStringSet(KEY_SEEN_LICENSES, seen - key).apply()
    }

    fun notifyPendingLicenses(context: Context, requests: List<MainPendingLicenseRequest>) {
        if (!MainDeviceRegistry.isLocallyOwner(context)) return
        NotifStore.init(context.applicationContext)

        val liveKeys = requests.map { "theone-license|" + it.id }.toSet()
        requests.forEach { request ->
            val who = request.person.ifBlank { "Onbekende gebruiker" }
            val key = "theone-license|" + request.id
            val appName = when (request.app) {
                "dj" -> "The One DJ"
                "music" -> "The One Music"
                else -> "The One Main"
            }
            val created = runCatching {
                java.time.Instant.parse(request.createdAt).toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())

            NotifStore.addOrUpdate(NotifItem(
                key = key,
                packageName = "the.one.license.requests",
                appLabel = appName,
                title = "Nieuwe $appName-aanmelding: $who",
                text = "$who vraagt een nieuwe $appName-installatie aan. Goedkeuren via Laptop → Apparaten beheren.",
                postTime = created,
                hasReplyAction = false,
                persistent = true,
                actionType = "license_request",
                actionValue = request.id,
                ongoing = true
            ))
        }
        // Only owner-approved / server-resolved requests vanish. Never remove
        // cached requests following network errors or an empty local refresh.
        NotifStore.removeWhere(includePersistent = true) { item ->
            item.actionType == "license_request" && item.key !in liveKeys
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = preferences.getStringSet(KEY_SEEN_LICENSES, emptySet()).orEmpty().toMutableSet()
        val old = seen.filter { it !in liveKeys }
        old.forEach { key -> manager.cancel(("license|" + key).hashCode()) }
        seen.removeAll(old.toSet())

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            ensureChannel(context)
            requests.forEach { request ->
                val key = "theone-license|" + request.id
                if (seen.contains(key)) return@forEach
                val who = request.person.ifBlank { "Onbekende gebruiker" }
                val intent = Intent(context, WakePcActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_access_management", true)
                    putExtra("focus_license_request_id", request.id)
                }
                val open = PendingIntent.getActivity(context, key.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("The One Main: nieuwe aanmelding van $who")
                    .setContentText("Wacht op jouw goedkeuring in Apparaten beheren.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(open)
                    .build()
                manager.notify(("license|" + key).hashCode(), notification)
                seen.add(key)
            }
        }
        preferences.edit().putStringSet(KEY_SEEN_LICENSES, seen.toSet()).apply()
    }

    private const val CHANNEL_ID = "the_one_access_requests"
    private const val PREFS = "access_request_notifications"
    private const val KEY_SEEN = "seen_keys"
    private const val KEY_SEEN_LICENSES = "seen_license_keys"

    fun notifyNew(
        context: Context,
        requests: List<MainPendingAccessRequest>
    ) {
        if (!MainDeviceRegistry.isLocallyOwner(context)) return

        NotifStore.init(context.applicationContext)

        val activeKeys = requests.map { requestKey(it) }.toSet()
        val activeNotifKeys = activeKeys.map { "theone-access|" + it }.toSet()

        // Dismissing a message never approves or rejects the actual request.
        requests.forEach { request ->
            val person = request.personName.ifBlank {
                request.deviceName.ifBlank { "Iemand" }
            }
            val scopeLabel = scopeLabel(request.scope)
            val key = "theone-access|" + requestKey(request)
            val time = runCatching {
                java.time.Instant.parse(request.requestedAt).toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())

            NotifStore.addOrUpdate(
                NotifItem(
                    key = key,
                    packageName = "the.one.access.requests",
                    appLabel = "The One Rechten",
                    title = "Toegangsverzoek van $person",
                    text = "$person vraagt toestemming voor $scopeLabel.",
                    postTime = time,
                    hasReplyAction = false,
                    persistent = true,
                    actionType = "access_request",
                    actionValue = request.deviceId
                )
            )
        }

        // Alleen behandelde aanvragen verdwijnen uit Main Meldingen.
        NotifStore.removeWhere(includePersistent = true) { item ->
            item.actionType == "access_request" && item.key !in activeNotifKeys
        }

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()

        // Verwijder systeemmeldingen zodra de aanvraag is behandeld.
        val stale = seen.filter { it !in activeKeys }
        stale.forEach { key -> manager.cancel(("access|" + key).hashCode()) }
        seen.removeAll(stale.toSet())

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            ensureChannel(context)

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
                    putExtra("focus_device_id", request.deviceId)
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
                                "Tik om de aanvraag te behandelen."
                        )
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(pendingIntent)
                    .build()

                manager.notify(("access|" + key).hashCode(), notification)
                seen += key
            }
        }

        prefs.edit().putStringSet(KEY_SEEN, seen).apply()
    }

    private fun requestKey(request: MainPendingAccessRequest): String =
        request.deviceId + "|" + request.scope + "|" + request.requestedAt

    private fun scopeLabel(scope: String): String = when (scope) {
        MainDeviceRegistry.ACCESS_MIXES -> "The One Mixes"
        MainDeviceRegistry.ACCESS_SHARED -> "Shared Media"
        MainDeviceRegistry.ACCESS_FAVORITES -> "Favorites"
        MainDeviceRegistry.ACCESS_DJ -> "The One DJ import"
        MainDeviceRegistry.ACCESS_RUN -> "The One Run"
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
