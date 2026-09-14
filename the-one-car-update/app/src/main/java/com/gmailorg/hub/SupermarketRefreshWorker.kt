package com.gmailorg.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Ververst op de achtergrond, elke 15 minuten (het kortst toegestane
 * interval voor periodieke taken op Android), de supermarkt-geofences naar
 * de actuele locatie — zodat meldingen blijven werken ook als je ver van je
 * oorspronkelijke locatie bent, zonder dat je de app hoeft te openen.
 */
class SupermarketRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "supermarket_geofence_refresh"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SupermarketRefreshWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        if (!SupermarketGeofenceManager.isEnabled(applicationContext)) return Result.success()

        val hasPermission = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return Result.success()

        return withContext(Dispatchers.IO) {
            val success = suspendCancellableCoroutine<Boolean> { cont ->
                SupermarketGeofenceManager.enableForCurrentLocation(applicationContext) { ok, _ ->
                    if (cont.isActive) cont.resume(ok)
                }
            }
            if (success) Result.success() else Result.retry()
        }
    }
}
