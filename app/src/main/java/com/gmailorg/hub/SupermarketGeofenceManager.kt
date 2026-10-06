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
        val detail = proximityStatus(context)
        store.getString("last_error", "")?.takeIf { it.isNotBlank() }?.let { return it + "\n" + detail }
        val count = store.getStringSet("registered_ids", emptySet()).orEmpty().count { it.startsWith("supermarkt_") }
        if (count == 0) return "Nog geen supermarkten ingesteld. Tik op Controleren."
        ShoppingListStore.init(context.applicationContext)
        val pending = ShoppingListStore.getAll().count { !it.done }
        return if (pending == 0) "Meldingen ingesteld voor $count supermarkten. Je lijst bevat geen openstaande boodschappen."
        else "Meldingen ingesteld voor $count supermarkten · $pending openstaande boodschappen.\n$detail"
    }

    private fun proximityStatus(context: Context): String {
        val store = prefs(context)
        val checked = store.getLong("proximity_checked", 0)
        if (checked == 0L) return "Afstand nog niet gecontroleerd. Melding bij een winkel binnen 180 meter."
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(checked))
        return "Controle $time: " + store.getString("proximity_status", "locatie onbekend") +
            "\nWinkelgegevens: © OpenStreetMap contributors · ODbL."
    }

    /** Checks stored shops without any map request, including while a lookup is slow. */
    @SuppressLint("MissingPermission")
    fun checkStoredShopsAtCurrentLocation(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || !hasLocationPermission(app)) return
        LocationServices.getFusedLocationProviderClient(app)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) checkCurrentProximity(app, location, readCachedSupermarkets(app))
                else prefs(app).edit().putLong("proximity_checked", System.currentTimeMillis())
                    .putString("proximity_status", "geen actuele locatie; afstand niet te bepalen.").apply()
            }
            .addOnFailureListener {
                prefs(app).edit().putLong("proximity_checked", System.currentTimeMillis())
                    .putString("proximity_status", "locatie ophalen mislukt; afstand niet te bepalen.").apply()
            }
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
            val age=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1000000
            if (age !in 0..SupermarketReminderPolicy.MAX_FIX_AGE_MS ||
                !location.hasAccuracy() || !location.accuracy.isFinite() ||
                location.accuracy <= 0 || location.accuracy > GEOFENCE_RADIUS_METERS) {
                fail("Geen voldoende nauwkeurige actuele locatie; Main probeert het opnieuw.");return
            }
            checkCurrentProximity(app, location, readCachedSupermarkets(app))
            fun refreshLookup() {
            fetchNearbySupermarkets(location.latitude, location.longitude) { lookup ->
                val supermarkets = lookup.points
                if(!isEnabled(app)){onResult(false,"Supermarktmeldingen zijn uitgezet.");return@fetchNearbySupermarkets}
                val stored=readCachedSupermarkets(app)
                val nearby=if(supermarkets.isNotEmpty())supermarkets else stored.filter { point ->
                    val distance=FloatArray(1)
                    Location.distanceBetween(location.latitude,location.longitude,point.first,point.second,distance)
                    distance[0]<=SEARCH_RADIUS_METERS
                }.sortedBy { point ->
                    val distance=FloatArray(1)
                    Location.distanceBetween(location.latitude,location.longitude,point.first,point.second,distance)
                    distance[0]
                }.take(MAX_GEOFENCES)
                if(nearby.isEmpty()){
                    fail(if (lookup.error != null) "Supermarkten ophalen mislukt: ${lookup.error}. Main probeert het opnieuw."
                        else "De kaartbron heeft geen supermarkt binnen 5 km van je huidige locatie gevonden. Tik op Controleren om opnieuw te zoeken.")
                    return@fetchNearbySupermarkets
                }
                if (supermarkets.isNotEmpty()) cacheSupermarkets(app, supermarkets)
                // A lookup can take longer than the location's useful lifetime.
                // Never infer current arrival from its old search centre.
                checkStoredShopsAtCurrentLocation(app)
                registerGeofences(app,nearby, location.latitude to location.longitude){success,message->
                    if(success){
                        prefs(app).edit().putString("last_error", if (lookup.error != null) "Live kaart niet bereikbaar: ${lookup.error}. ${nearby.size} winkels uit opgeslagen gegevens en de meegeleverde Nederlandse kaart worden bewaakt." else "").putLong("last_registered",System.currentTimeMillis())
                            .putString("scan_lat", location.latitude.toString()).putString("scan_lon", location.longitude.toString()).apply()
                    }else prefs(app).edit().putString("last_error",message).apply()
                    onResult(success,message)
                }
            }
            }
            val cachedNearby = readCachedSupermarkets(app).map { point ->
                val distance = FloatArray(1)
                Location.distanceBetween(location.latitude,location.longitude,point.first,point.second,distance)
                point to distance[0]
            }.filter { it.second <= SEARCH_RADIUS_METERS }.sortedBy { it.second }
                .take(MAX_GEOFENCES).map { it.first }
            // Arm the offline catalog before waiting for public map servers.
            if (cachedNearby.isNotEmpty()) {
                registerGeofences(app,cachedNearby,location.latitude to location.longitude) { success, _ ->
                    if (success) prefs(app).edit()
                        .putString("scan_lat",location.latitude.toString())
                        .putString("scan_lon",location.longitude.toString())
                        .putString("last_error","Meegeleverde kaart actief voor ${cachedNearby.size} winkels. Live gegevens worden ververst.")
                        .apply()
                    refreshLookup()
                }
            } else refreshLookup()
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
        if (!isEnabled(context)) return
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1000000
        val distances = points.map { point ->
            val distance = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, point.first, point.second, distance)
            distance[0]
        }
        val nearest = distances.minOrNull()
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.NaN
        val near = nearest != null && SupermarketReminderPolicy.isNearby(nearest, accuracy, age)
        val status = when {
            age !in 0..SupermarketReminderPolicy.MAX_FIX_AGE_MS ->
                "locatie te oud; nog geen betrouwbare aankomstcontrole."
            !accuracy.isFinite() || accuracy <= 0 || accuracy > GEOFENCE_RADIUS_METERS ->
                "locatie onvoldoende nauwkeurig; nog geen betrouwbare aankomstcontrole."
            nearest == null || nearest > SEARCH_RADIUS_METERS ->
                "geen bekende supermarkt binnen 5 km; winkelgegevens moeten worden ververst."
            near -> SupermarketReminderDelivery.deliver(context).ifBlank { "Bij een bekende supermarkt." }
            else -> "dichtstbijzijnde bekende supermarkt op ${nearest.toInt()} meter. Melding binnen 180 meter."
        }
        ShoppingListWidgetProvider.setNearSupermarket(context, near)
        prefs(context).edit().putLong("proximity_checked", System.currentTimeMillis())
            .putString("proximity_status", status).apply()
    }
    private fun cacheSupermarkets(context: Context, points: List<Pair<Double,Double>>) {
        val array=org.json.JSONArray()
        for(point in points)array.put(org.json.JSONArray().put(point.first).put(point.second))
        prefs(context).edit().putString("supermarkets",array.toString()).apply()
    }
    private fun readCachedSupermarkets(context: Context): List<Pair<Double,Double>> = (runCatching {
        val array=org.json.JSONArray(prefs(context).getString("supermarkets","[]"))
        (0 until array.length()).map { i -> array.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }
    }.getOrDefault(emptyList()) + SupermarketOfflineCatalog.points(context)).distinct()

    /** Opnieuw registreren na een herstart van het toestel (geofences overleven een reboot niet). */
    @SuppressLint("MissingPermission")
    fun reArmAfterBootIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        val lat = prefs(context).getString("scan_lat", null)?.toDoubleOrNull()
        val lon = prefs(context).getString("scan_lon", null)?.toDoubleOrNull()
        val cached = readCachedSupermarkets(context).filter { point ->
            if (lat == null || lon == null) false else {
                val distance = FloatArray(1)
                Location.distanceBetween(lat,lon,point.first,point.second,distance)
                distance[0] <= SEARCH_RADIUS_METERS
            }
        }.sortedBy { point ->
            val distance = FloatArray(1)
            Location.distanceBetween(lat!!,lon!!,point.first,point.second,distance)
            distance[0]
        }.take(MAX_GEOFENCES)
        if(cached.isNotEmpty()&&hasLocationPermission(context)&&hasBackgroundLocationPermission(context)) {
            val center = if (lat != null && lon != null) lat to lon else null
            registerGeofences(context,cached,center){_,_->}
        }
        SupermarketRefreshWorker.schedule(context)
        SupermarketRefreshWorker.refreshNow(context)
    }

    private data class Lookup(val points: List<Pair<Double, Double>>, val error: String? = null)
    private val pendingLookups = mutableMapOf<String, MutableList<(Lookup) -> Unit>>()

    private fun fetchNearbySupermarkets(
        lat: Double,
        lon: Double,
        callback: (Lookup) -> Unit
    ) {
        val key = java.lang.String.format(java.util.Locale.US, "%.3f,%.3f", lat, lon)
        synchronized(pendingLookups) {
            val existing = pendingLookups[key]
            if (existing != null) {
                existing.add(callback)
                return
            }
            pendingLookups[key] = mutableListOf(callback)
        }
        Thread {
            val query = """
                [out:json][timeout:12];
                nwr["shop"="supermarket"](around:$SEARCH_RADIUS_METERS,$lat,$lon);
                out center;
            """.trimIndent()
            val endpoints = listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter",
                "https://overpass.private.coffee/api/interpreter"
            )
            var result: Lookup? = null
            var emptyResponse = false
            var lastFailure = "geen verbinding met de kaartservers"
            for (endpoint in endpoints) {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8000
                    connection.readTimeout = 18000
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "TheOneMain/1.0 (supermarket-reminders)")
                    val request = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(request.size)
                    connection.outputStream.use { it.write(request) }
                    val status = connection.responseCode
                    require(status == 200) { "HTTP $status" }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val points = SupermarketLookupParser.parse(body)
                    val sorted = points.distinct().sortedBy { point ->
                        val distance = FloatArray(1)
                        Location.distanceBetween(lat,lon,point.first,point.second,distance)
                        distance[0]
                    }.take(MAX_GEOFENCES)
                    if (sorted.isNotEmpty()) { result = Lookup(sorted); break }
                    emptyResponse = true
                } catch (e: Exception) {
                    lastFailure = when (e) {
                        is java.net.SocketTimeoutException -> "kaartserver reageert niet op tijd"
                        is java.net.UnknownHostException -> "kaartserver niet bereikbaar; controleer internet"
                        else -> e.message?.takeIf { it.startsWith("HTTP ") } ?: "kaartserver gaf geen bruikbare winkelgegevens"
                    }
                    Log.w(TAG, "Supermarktzoekopdracht mislukt bij " + URL(endpoint).host, e)
                } finally {
                    connection.disconnect()
                }
            }
            val completed = result ?: Lookup(emptyList(), if (emptyResponse) null else lastFailure)
            mainHandler.post {
                val callbacks = synchronized(pendingLookups) { pendingLookups.remove(key).orEmpty() }
                callbacks.forEach { it(completed) }
            }
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
