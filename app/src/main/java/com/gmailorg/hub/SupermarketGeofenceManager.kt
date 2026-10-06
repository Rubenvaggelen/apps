package com.gmailorg.hub

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.Priority
import android.util.Base64
import android.app.NotificationManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
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
    private const val GEOFENCE_RADIUS_METERS = SupermarketReminderPolicy.RADIUS_METERS
    private const val SEARCH_RADIUS_METERS = 5000 // 5 km rondom de gebruiker
    private const val MAX_GEOFENCES = 40 // ruim onder de limiet van 100 per app
    const val REFRESH_ANCHOR_ID = "supermarket_refresh_anchor"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun hasBackgroundLocationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun requestEnable(context: Context) { setEnabled(context,true); SupermarketRefreshWorker.schedule(context); SupermarketRefreshWorker.refreshNow(context) }

    fun statusText(context: Context): String {
        if (!isEnabled(context)) return "Supermarktmeldingen staan uit."
        SupermarketReminderDelivery.blockedReason(context)?.let { return it }
        if (!hasLocationPermission(context)) return "Nauwkeurige locatietoegang ontbreekt. Tik op Controleren."
        if (!hasBackgroundLocationPermission(context)) return "Locatie staat niet op Altijd toestaan. Tik op Controleren."
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) return "Zet de locatie van je telefoon aan."
        val store = prefs(context)
        store.getString("last_error", "")?.takeIf { it.isNotBlank() }?.let { return it }
        val count = store.getStringSet("registered_ids", emptySet()).orEmpty().count { it.startsWith("supermarkt_") }
        if (count == 0) return "Nog geen supermarkten ingesteld. Tik op Controleren."
        ShoppingListStore.init(context.applicationContext)
        val pending = ShoppingListStore.getAll().count { !it.done }
        return if (pending == 0) "Meldingen ingesteld voor $count supermarkten. Je lijst bevat geen openstaande boodschappen."
        else "Meldingen ingesteld voor $count supermarkten · $pending openstaande boodschappen. Android kan aankomst enkele minuten later melden."
    }

    private fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun disable(context: Context) {
        setEnabled(context, false)
        SupermarketRefreshWorker.cancel(context)
        val client = LocationServices.getGeofencingClient(context)
        client.removeGeofences(geofencePendingIntent(context))
    }

    @SuppressLint("MissingPermission")
    fun enableForCurrentLocation(
        context: Context,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val app=context.applicationContext
        setEnabled(app, true)
        fun fail(message: String) { prefs(app).edit().putString("last_error",message).apply(); onResult(false,message) }
        if(!hasLocationPermission(app)){fail("Geef Main nauwkeurige locatietoegang.");return}
        if(!hasBackgroundLocationPermission(app)){fail("Zet locatietoegang voor Main op Altijd toestaan.");return}
        SupermarketReminderDelivery.blockedReason(app)?.let { fail(it); return }
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(app.getSystemService(LocationManager::class.java))) { fail("Zet de locatie van je telefoon aan."); return }
        val fused = LocationServices.getFusedLocationProviderClient(app)
        fun useLocation(location: Location?) {
            if(!isEnabled(app)){onResult(false,"Supermarktmeldingen zijn uitgezet.");return}
            if(location==null){fail("Geen actuele locatie. Zet locatie aan en probeer opnieuw.");return}
            checkCurrentProximity(app, location, readCachedSupermarkets(app))
            fetchNearbySupermarkets(location.latitude, location.longitude) { supermarkets ->
                if(!isEnabled(app)){onResult(false,"Supermarktmeldingen zijn uitgezet.");return@fetchNearbySupermarkets}
                val stored=readCachedSupermarkets(app)
                val nearby=if(supermarkets.isNotEmpty())supermarkets else stored.filter { point ->
                    val distance=FloatArray(1)
                    Location.distanceBetween(location.latitude,location.longitude,point.first,point.second,distance)
                    distance[0]<=SEARCH_RADIUS_METERS
                }
                if(nearby.isEmpty()){fail("Supermarkten ophalen mislukt of geen supermarkt binnen 5 km. Main probeert het opnieuw.");return@fetchNearbySupermarkets}
                if (supermarkets.isNotEmpty()) cacheSupermarkets(app, supermarkets)
                checkCurrentProximity(app, location, nearby)
                registerGeofences(app,nearby, location.latitude to location.longitude){success,message->
                    if(success){
                        prefs(app).edit().putString("last_error","").putLong("last_registered",System.currentTimeMillis())
                            .putString("scan_lat", location.latitude.toString()).putString("scan_lon", location.longitude.toString()).apply()
                    }else prefs(app).edit().putString("last_error",message).apply()
                    onResult(success,message)
                }
            }
        }
        fused.lastLocation.addOnSuccessListener { location: Location? ->
            val age=if(location==null)Long.MAX_VALUE else (SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1000000
            if(location!=null && location.hasAccuracy() && location.accuracy <= GEOFENCE_RADIUS_METERS && age in 0..120000){useLocation(location)}
            else fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,null)
                .addOnSuccessListener { fresh -> useLocation(fresh) }
                .addOnFailureListener { fail("Actuele locatie ophalen mislukt. Controleer locatietoegang.") }
        }.addOnFailureListener { fail("Locatie ophalen mislukt. Controleer locatietoegang.") }
    }
    private fun checkCurrentProximity(context: Context, location: Location, points: List<Pair<Double, Double>>) {
        if (!isEnabled(context) || !location.hasAccuracy()) return
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1000000
        if (points.any { point ->
                val distance = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, point.first, point.second, distance)
                SupermarketReminderPolicy.isNearby(distance[0], location.accuracy, age)
            }) {
            ShoppingListWidgetProvider.setNearSupermarket(context, true)
            SupermarketReminderDelivery.deliver(context)
        }
    }
    private fun cacheSupermarkets(context: Context, points: List<Pair<Double,Double>>) {
        val array=org.json.JSONArray()
        for(point in points)array.put(org.json.JSONArray().put(point.first).put(point.second))
        prefs(context).edit().putString("supermarkets",array.toString()).apply()
    }
    private fun readCachedSupermarkets(context: Context): List<Pair<Double,Double>> = runCatching {
        val array=org.json.JSONArray(prefs(context).getString("supermarkets","[]"))
        (0 until array.length()).map { i -> array.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }
    }.getOrDefault(emptyList())

    /** Opnieuw registreren na een herstart van het toestel (geofences overleven een reboot niet). */
    @SuppressLint("MissingPermission")
    fun reArmAfterBootIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        val cached=readCachedSupermarkets(context)
        if(cached.isNotEmpty()&&hasLocationPermission(context)&&hasBackgroundLocationPermission(context)) {
            val lat = prefs(context).getString("scan_lat", null)?.toDoubleOrNull()
            val lon = prefs(context).getString("scan_lon", null)?.toDoubleOrNull()
            val center = if (lat != null && lon != null) lat to lon else null
            registerGeofences(context,cached,center){_,_->}
        }
        SupermarketRefreshWorker.schedule(context)
        SupermarketRefreshWorker.refreshNow(context)
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
                    nwr["shop"="supermarket"](around:$SEARCH_RADIUS_METERS,$lat,$lon);
                    out center;
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
                    val point=el.optJSONObject("center")?:el
                    if(point.has("lat")&&point.has("lon"))results.add(Pair(point.getDouble("lat"),point.getDouble("lon")))
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Supermarkten ophalen mislukt", e)
            }
            val sorted=results.distinct().sortedBy { point ->
                val distance=FloatArray(1);Location.distanceBetween(lat,lon,point.first,point.second,distance);distance[0]
            }.take(MAX_GEOFENCES)
            mainHandler.post { callback(sorted) }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofences(context: Context, supermarkets: List<Pair<Double, Double>>, center: Pair<Double, Double>? = null, onResult: (Boolean,String)->Unit) {
        val client: GeofencingClient = LocationServices.getGeofencingClient(context)
        val geofences = supermarkets.map { (lat, lon) ->
            Geofence.Builder()
                .setRequestId("supermarkt_"+lat.toString()+"_"+lon.toString())
                .setCircularRegion(lat, lon, GEOFENCE_RADIUS_METERS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
                .setLoiteringDelay(30000)
                .setNotificationResponsiveness(60000)
                .build()
        }.toMutableList()
        center?.let { (lat, lon) ->
            geofences.add(Geofence.Builder().setRequestId(REFRESH_ANCHOR_ID)
                .setCircularRegion(lat, lon, 2000f).setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(60000).build())
        }

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(geofences)
            .build()

        if(!isEnabled(context)){onResult(false,"Supermarktmeldingen zijn uitgezet.");return}
        try {
            client.addGeofences(geofencingRequest,geofencePendingIntent(context))
                .addOnSuccessListener {
                    if(!isEnabled(context)){client.removeGeofences(geofencePendingIntent(context));onResult(false,"Supermarktmeldingen zijn uitgezet.")}
                    else {
                        val ids=geofences.map { it.requestId }.toSet()
                        val old=prefs(context).getStringSet("registered_ids",emptySet()).orEmpty()-ids
                        prefs(context).edit().putStringSet("registered_ids",ids).apply()
                        if(old.isNotEmpty())client.removeGeofences(old.toList())
                        onResult(true,"Meldingen actief voor "+supermarkets.size+" supermarkt(en).")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG,"Geofences registreren mislukt",e)
                    onResult(false,"Android kon supermarktmeldingen niet registreren. Controleer locatie, Altijd toestaan en Google Play-services.")
                }
        } catch(e: SecurityException) { onResult(false,"Geef Main nauwkeurige locatie en Altijd toestaan.") }
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
