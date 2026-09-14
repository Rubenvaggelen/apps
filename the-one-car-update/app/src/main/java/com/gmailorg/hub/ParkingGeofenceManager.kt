package com.gmailorg.hub

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * Registreert een geofence-melding ("Vergeet niet te betalen voor parkeren!")
 * voor elk adres in ParkingAddressStore. Adressen worden éénmalig omgezet
 * naar coördinaten (Geocoder), daarna gecached in de store zodat we niet
 * telkens opnieuw hoeven te geocoderen.
 */
object ParkingGeofenceManager {

    private const val TAG = "ParkingGeofence"
    private const val RADIUS_METERS = 50f
    private const val REQUEST_PREFIX = "parkeren_"

    // Andere request code dan de supermarkt-geofences (die gebruiken 0), zodat
    // het in-/uitschakelen van supermarkt-meldingen deze geofences niet raakt.
    private const val PENDING_INTENT_REQUEST_CODE = 1

    private val mainHandler = Handler(Looper.getMainLooper())

    fun requestIdFor(addressId: String) = "$REQUEST_PREFIX$addressId"

    fun addressIdFromRequestId(requestId: String): String? =
        if (requestId.startsWith(REQUEST_PREFIX)) requestId.removePrefix(REQUEST_PREFIX) else null

    fun hasLocationPermission(context: Context): Boolean {
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
     * Registreert geofences voor alle adressen in de store waarvoor dat nog
     * niet gebeurd is (of nog geen coördinaten bekend zijn). Veilig om vaker
     * aan te roepen: bv. bij elke app-start, na een reboot, en na het
     * toevoegen van een nieuw adres.
     */
    fun syncAll(context: Context) {
        val appContext = context.applicationContext
        if (!hasLocationPermission(appContext)) {
            Log.d(TAG, "Nog geen locatietoestemming, sla parkeer-geofences over.")
            return
        }
        ParkingAddressStore.init(appContext)
        val addresses = ParkingAddressStore.getAll()

        val ready = addresses.filter { it.lat != null && it.lon != null }
        if (ready.isNotEmpty()) {
            registerGeofences(appContext, ready.map { Triple(it.id, it.lat!!, it.lon!!) })
        }

        val toResolve = addresses.filter { it.lat == null || it.lon == null }
        toResolve.forEach { resolveAndRegister(appContext, it.id, it.address) }
    }

    /** Geocodeert en registreert één nieuw toegevoegd adres. */
    fun registerNewAddress(context: Context, addressId: String, addressText: String) {
        val appContext = context.applicationContext
        if (!hasLocationPermission(appContext)) return
        resolveAndRegister(appContext, addressId, addressText)
    }

    /** Verwijdert de geofence voor een adres dat uit de lijst is gehaald. */
    fun unregister(context: Context, addressId: String) {
        val client = LocationServices.getGeofencingClient(context.applicationContext)
        client.removeGeofences(listOf(requestIdFor(addressId)))
            .addOnFailureListener { e -> Log.e(TAG, "Parkeer-geofence verwijderen mislukt", e) }
    }

    private fun resolveAndRegister(context: Context, addressId: String, addressText: String) {
        Thread {
            var lat: Double? = null
            var lon: Double? = null
            try {
                val geocoder = Geocoder(context, Locale("nl", "NL"))
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocationName(addressText, 1)
                val hit = results?.firstOrNull()
                if (hit != null) {
                    lat = hit.latitude
                    lon = hit.longitude
                }
            } catch (e: Exception) {
                Log.e(TAG, "Geocoding van '$addressText' mislukt", e)
            }
            mainHandler.post {
                if (lat != null && lon != null) {
                    ParkingAddressStore.updateCoords(addressId, lat, lon)
                    registerGeofences(context, listOf(Triple(addressId, lat, lon)))
                } else {
                    Log.e(TAG, "Kon adres niet omzetten naar coördinaten: $addressText")
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofences(context: Context, addresses: List<Triple<String, Double, Double>>) {
        if (addresses.isEmpty()) return
        val client: GeofencingClient = LocationServices.getGeofencingClient(context)
        val geofences = addresses.map { (id, lat, lon) ->
            Geofence.Builder()
                .setRequestId(requestIdFor(id))
                .setCircularRegion(lat, lon, RADIUS_METERS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        client.addGeofences(request, pendingIntent(context))
            .addOnSuccessListener { Log.d(TAG, "${geofences.size} parkeer-geofence(s) geregistreerd") }
            .addOnFailureListener { e -> Log.e(TAG, "Parkeer-geofences registreren mislukt", e) }
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
