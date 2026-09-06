package com.gmailorg.hub

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Zoekt supermarkten bij een locatie op (gratis, via OpenStreetMap's Overpass
 * API) en registreert daar Android-geofences omheen. Zodra je zo'n gebied
 * binnenkomt, stuurt GeofenceBroadcastReceiver een melding als er nog iets
 * op de boodschappenlijst staat.
 */
object SupermarketGeofenceManager {

    private const val TAG = "SupermarketGeofence"
    private const val PREFS = "household_geofence_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val GEOFENCE_RADIUS_METERS = 150f
    private const val SEARCH_RADIUS_METERS = 5000 // 5 km rondom de gebruiker
    private const val MAX_GEOFENCES = 40 // ruim onder de limiet van 100 per app

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    private fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun disable(context: Context) {
        setEnabled(context, false)
        val client = LocationServices.getGeofencingClient(context)
        client.removeGeofences(geofencePendingIntent(context))
    }

    @SuppressLint("MissingPermission")
    fun enableForCurrentLocation(
        context: Context,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        fused.lastLocation.addOnSuccessListener { location: Location? ->
            if (location == null) {
                onResult(false, "Kon je huidige locatie niet bepalen. Probeer het buiten opnieuw.")
                return@addOnSuccessListener
            }
            fetchNearbySupermarkets(location.latitude, location.longitude) { supermarkets ->
                if (supermarkets.isEmpty()) {
                    onResult(false, "Geen supermarkten gevonden binnen 5 km.")
                    return@fetchNearbySupermarkets
                }
                registerGeofences(context, supermarkets)
                setEnabled(context, true)
                onResult(true, "Meldingen aan voor ${supermarkets.size} supermarkt(en) in de buurt.")
            }
        }.addOnFailureListener {
            onResult(false, "Kon je huidige locatie niet ophalen.")
        }
    }

    /** Opnieuw registreren na een herstart van het toestel (geofences overleven een reboot niet). */
    @SuppressLint("MissingPermission")
    fun reArmAfterBootIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        enableForCurrentLocation(context) { _, _ -> }
    }

    private fun fetchNearbySupermarkets(
        lat: Double,
        lon: Double,
        callback: (List<Pair<Double, Double>>) -> Unit
    ) {
        Thread {
            val results = mutableListOf<Pair<Double, Double>>()
            try {
                val query = """
                    [out:json][timeout:15];
                    node["shop"="supermarket"](around:$SEARCH_RADIUS_METERS,$lat,$lon);
                    out body $MAX_GEOFENCES;
                """.trimIndent()
                val url = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8"))
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val elements = json.getJSONArray("elements")
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    results.add(Pair(el.getDouble("lat"), el.getDouble("lon")))
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Supermarkten ophalen mislukt", e)
            }
            mainHandler.post { callback(results) }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofences(context: Context, supermarkets: List<Pair<Double, Double>>) {
        val client: GeofencingClient = LocationServices.getGeofencingClient(context)
        val geofences = supermarkets.mapIndexed { index, (lat, lon) ->
            Geofence.Builder()
                .setRequestId("supermarkt_$index")
                .setCircularRegion(lat, lon, GEOFENCE_RADIUS_METERS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        client.removeGeofences(geofencePendingIntent(context)).addOnCompleteListener {
            client.addGeofences(geofencingRequest, geofencePendingIntent(context))
                .addOnFailureListener { e -> Log.e(TAG, "Geofences registreren mislukt", e) }
        }
    }

    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
