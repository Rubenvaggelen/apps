package com.gmailorg.hub

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.util.Locale

/**
 * Vaste, altijd-aan geofence-melding: "Vergeet niet te betalen voor parkeren!"
 * zodra je aankomt bij Snackcident (Vianenstraat 31, Amsterdam). In
 * tegenstelling tot de supermarkt-meldingen is hier geen aan/uit-schakelaar
 * voor nodig; hij registreert zichzelf zodra locatietoestemming beschikbaar is
 * (bv. na het aanzetten van de supermarkt-meldingen op het Huishouden-scherm).
 */
object ParkingGeofenceManager {

    const val REQUEST_ID = "snackcident_parkeren"
    private const val TAG = "ParkingGeofence"
    private const val ADDRESS = "Vianenstraat 31, Amsterdam"
    private const val RADIUS_METERS = 50f

    // Andere request code dan de supermarkt-geofences (die gebruiken 0), zodat
    // het in-/uitschakelen van supermarkt-meldingen deze geofence niet raakt.
    private const val PENDING_INTENT_REQUEST_CODE = 1

    private const val PREFS = "parking_geofence_prefs"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hasLocationPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /**
     * Registreert de geofence als locatietoestemming aanwezig is. Veilig om
     * vaker aan te roepen (bv. bij elke app-start en na een reboot) — als de
     * geofence al geregistreerd is, gebeurt er niets nieuws.
     */
    fun ensureRegistered(context: Context) {
        val appContext = context.applicationContext
        if (!hasLocationPermission(appContext)) {
            Log.d(TAG, "Nog geen locatietoestemming, sla parkeer-geofence over.")
            return
        }

        val cachedLat = prefs(appContext).getFloat(KEY_LAT, Float.NaN)
        val cachedLon = prefs(appContext).getFloat(KEY_LON, Float.NaN)
        if (!cachedLat.isNaN() && !cachedLon.isNaN()) {
            registerGeofence(appContext, cachedLat.toDouble(), cachedLon.toDouble())
            return
        }

        resolveAddress(appContext) { lat, lon ->
            if (lat != null && lon != null) {
                prefs(appContext).edit()
                    .putFloat(KEY_LAT, lat.toFloat())
                    .putFloat(KEY_LON, lon.toFloat())
                    .apply()
                registerGeofence(appContext, lat, lon)
            } else {
                Log.e(TAG, "Kon adres niet omzetten naar coördinaten: $ADDRESS")
            }
        }
    }

    private fun resolveAddress(context: Context, callback: (Double?, Double?) -> Unit) {
        Thread {
            var lat: Double? = null
            var lon: Double? = null
            try {
                val geocoder = Geocoder(context, Locale("nl", "NL"))
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocationName(ADDRESS, 1)
                val hit = results?.firstOrNull()
                if (hit != null) {
                    lat = hit.latitude
                    lon = hit.longitude
                }
            } catch (e: Exception) {
                Log.e(TAG, "Geocoding van '$ADDRESS' mislukt", e)
            }
            mainHandler.post { callback(lat, lon) }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofence(context: Context, lat: Double, lon: Double) {
        val client: GeofencingClient = LocationServices.getGeofencingClient(context)
        val geofence = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(lat, lon, RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        client.addGeofences(request, pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "Parkeer-geofence geregistreerd op $lat,$lon") }
            .addOnFailureListener { e -> Log.e(TAG, "Parkeer-geofence registreren mislukt", e) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
