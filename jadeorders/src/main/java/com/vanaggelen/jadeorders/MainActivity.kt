package com.vanaggelen.jadeorders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.widget.ImageView
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private enum class Role { NONE, CUSTOMER, BUSINESS }
    data class Product(val name: String, val price: Double, val category: String, val description: String)
    data class Order(val id: Int, val items: LinkedHashMap<String, Int>, val total: Double, var status: String, val trackingToken: String = "", val delivery: Boolean = false, val address: String = "", val postcode: String = "", val deliveryFee: Double = 0.0, val paymentMethod: String = "", val paymentUrl: String = "", val paymentStatus: String = "")
    data class Announcement(val title: String = "", val message: String = "", val from: String = "", val until: String = "", val active: Boolean = false, val orderingBlocked: Boolean = false)

    private val products = listOf(
        Product("BBQ Regular", 10.00, "BBQ", "1 bout • 2 stokjes saté • salade"),
        Product("BBQ Extra", 15.00, "BBQ", "2 bouten • 4 stokjes saté • salade"),
        Product("BBQ Gezin", 25.00, "BBQ", "3 bouten • 6 stokjes saté • 3 salades"),
        Product("Extra bout", 3.00, "Extra's", "Los bij te bestellen"),
        Product("Extra salade", 2.50, "Extra's", "Los bij te bestellen"),
        Product("Portie saté", 5.00, "Extra's", "Los bij te bestellen")
    )
    private val cart = linkedMapOf<String, Int>()
    private var deliverySelected = false
    private var deliveryAddress = ""
    private var deliveryPostcode = ""
    private var deliveryPaymentMethod = "Cash"
    private var deliveryPaymentPhone = ""
    private val orderRoutes = mutableMapOf<Int, String>()
    private lateinit var root: LinearLayout
    private lateinit var nearby: ConnectionsClient
    private val money = NumberFormat.getCurrencyInstance(Locale("nl", "NL"))
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val permissionRequest = 4041
    private val notificationPermissionRequest = 4042
    private val orderNotificationChannel = "rutu_order_updates"
    private var appInForeground = false
    private var role = Role.NONE
    private var connectedEndpoint: String? = null
    private var connectionText = "Niet verbonden"
    private var pendingStart = false
    private var screen = "landing"
    private var renderedScreen = ""
    private var activeScroll: ScrollView? = null
    private val scrollPositions = mutableMapOf<String, Int>()
    private val serviceId by lazy { "$packageName.rutubbq.v1" }

    private fun normalizedPostcode(value: String): String = value.uppercase(Locale.ROOT).replace(" ", "")
    private fun validPostcode(value: String): Boolean = Regex("^\\d{4}[A-Z]{2}$").matches(normalizedPostcode(value))
    private fun deliveryFeeFor(value: String): Double = if (normalizedPostcode(value).take(4) == "1106") 2.50 else 5.00
    private fun displayPostcode(value: String): String {
        val p = normalizedPostcode(value)
        return if (p.length == 6) "${p.take(4)} ${p.drop(4)}" else value.trim()
    }

    private fun customerName(): String =
        getSharedPreferences("rutu_customer", Context.MODE_PRIVATE).getString("name", "").orEmpty().trim()

    private fun saveCustomerName(name: String) {
        getSharedPreferences("rutu_customer", Context.MODE_PRIVATE).edit().putString("name", name.trim()).apply()
    }

    private fun ensureCustomerName() {
        if (customerName().isNotBlank()) return
        val input = EditText(this).apply {
            hint = "Jouw naam"
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Welkom bij Rutu BBQ")
            .setMessage("Vul je naam in. Deze naam ziet Rutu BBQ bij je bestelling.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Opslaan", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString().orEmpty().trim()
                if (name.length < 2) {
                    input.error = "Vul je naam in"
                    return@setOnClickListener
                }
                saveCustomerName(name.take(60))
                dialog.dismiss()
                if (role == Role.CUSTOMER) refreshRoleScreen()
            }
        }
        dialog.show()
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val onlineApiBase = "https://rubenvanaggelen.com/rutu-api/index.php"
    @Volatile private var onlineAvailable = false
    private var onlineText = "Online verbinding controleren…"
    @Volatile private var announcement = Announcement()
    private var lastAnnouncementCheck = 0L
    private var lastStatusRefreshText = "Status wordt automatisch bijgewerkt"
    private val onlinePoller = object : Runnable {
        override fun run() {
            if (role == Role.CUSTOMER) {
                syncOnlineStatuses()
                val now = System.currentTimeMillis()
                if (now - lastAnnouncementCheck >= 15000L) {
                    lastAnnouncementCheck = now
                    syncAnnouncement(false)
                }
                uiHandler.postDelayed(this, 2000)
            }
        }
    }
    private var windowsHost = ""
    private var windowsConnected = false
    private var windowsText = "Windows bedrijf niet verbonden"
    private val windowsPoller = object : Runnable {
        override fun run() {
            if (windowsConnected && role == Role.CUSTOMER) {
                syncWindowsStatuses()
                uiHandler.postDelayed(this, 2500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearby = Nearby.getConnectionsClient(this)
        createOrderNotificationChannel()
        windowsHost = getSharedPreferences("rutu_windows", Context.MODE_PRIVATE).getString("host", "") ?: ""
        screen = "landing"
        renderedScreen = ""
        scrollPositions.clear()
        landing()
        ensureCustomerName()
        syncAnnouncement(true)
        ensureBackgroundOrderStatusService()
        syncOnlineStatuses()
        RutuUpdateChecker.checkForUpdate(this)
    }

    override fun onResume() {
        super.onResume()
        appInForeground = true
        syncAnnouncement(true)
        val bannerPrefs = getSharedPreferences("rutu_customer_banner", Context.MODE_PRIVATE)
        val bannerUntil = bannerPrefs.getLong("eat_well_until", 0L)
        if (bannerPrefs.getBoolean("force_landing", false) && bannerUntil > System.currentTimeMillis()) {
            bannerPrefs.edit().putBoolean("force_landing", false).apply()
            landing()
        }
        syncOnlineStatuses()
        if (role == Role.CUSTOMER) {
            uiHandler.removeCallbacks(onlinePoller)
            uiHandler.post(onlinePoller)
        }
    }

    override fun onPause() {
        appInForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(windowsPoller)
        uiHandler.removeCallbacks(onlinePoller)
        uiHandler.removeCallbacks(eatWellBannerWatch)
        uiHandler.removeCallbacks(eatWellBannerRefresh)
        nearby.stopAllEndpoints(); nearby.stopAdvertising(); nearby.stopDiscovery()
        super.onDestroy()
    }

    private fun permissionsNeeded(): Array<String> {
        val result = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            result += Manifest.permission.BLUETOOTH_SCAN
            result += Manifest.permission.BLUETOOTH_CONNECT
            result += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) result += Manifest.permission.NEARBY_WIFI_DEVICES
        else result += Manifest.permission.ACCESS_FINE_LOCATION
        return result.toTypedArray()
    }

    private fun hasPermissions() = permissionsNeeded().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureConnection() {
        if (hasPermissions()) startNearbyForRole() else {
            pendingStart = true
            ActivityCompat.requestPermissions(this, permissionsNeeded(), permissionRequest)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && pendingStart) {
            pendingStart = false
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startNearbyForRole()
            else toast("Toestemming is nodig voor een directe Android-naar-Android verbinding.")
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequest)
        }
    }

    private fun createOrderNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(orderNotificationChannel, "Bestelupdates", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Belangrijke updates over je Rutu BBQ-bestelling"
            }
        )
    }

    private fun notifyOrderStatus(orderId: Int, status: String) {
        val notificationPrefs = getSharedPreferences("rutu_notification_state", Context.MODE_PRIVATE)
        val notificationKey = "order_$orderId"
        if (notificationPrefs.getString(notificationKey, "") == status) return
        notificationPrefs.edit().putString(notificationKey, status).apply()
        if (status == "Afgerond") markEatWellBanner(orderId)
        val title = when (status) {
            "In bereiding" -> "Je bestelling wordt bereid"
            "Klaar" -> "Je bestelling is klaar"
            "Bestelling is onderweg" -> "Uw bestelling is onderweg"
            "Afgerond" -> "Uw bestelling is afgegeven. Eet u smakelijk."
            "Uitverkocht" -> "Uitverkocht"
            "Geweigerd" -> "Bestelling geweigerd"
            "Geannuleerd" -> "Bestelling geannuleerd"
            else -> "Bestelupdate"
        }
        val message = when (status) {
            "Uitverkocht" -> "Bestelling #$orderId is helaas uitverkocht."
            "Bestelling is onderweg" -> "Uw bestelling is onderweg."
            "Afgerond" -> "Uw bestelling is afgegeven. Eet u smakelijk."
            else -> "Bestelling #$orderId heeft nu status: $status."
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, orderId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, orderNotificationChannel)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pending)

        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(12000 + orderId, builder.build())
        }
    }

    private fun ensureBackgroundOrderStatusService() {
        val finalStatuses = setOf("Afgerond", "Geannuleerd", "Uitverkocht", "Geweigerd")
        val hasTrackedOrder = Store.all(this).any { it.trackingToken.isNotBlank() && it.status !in finalStatuses }
        if (!hasTrackedOrder) return
        val serviceIntent = Intent(this, OrderStatusService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, serviceIntent)
        else startService(serviceIntent)
    }

    private fun startNearbyForRole() = when (role) {
        Role.CUSTOMER -> startDiscovery()
        Role.BUSINESS -> startAdvertising()
        else -> Unit
    }

    private fun startDiscovery() {
        connectionText = "Android bedrijf zoeken…"; refreshRoleScreen()
        nearby.startDiscovery(serviceId, endpointDiscoveryCallback, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { connectionText = "Zoeken naar Android bedrijf…"; refreshRoleScreen() }
            .addOnFailureListener { connectionText = "Zoeken mislukt"; refreshRoleScreen() }
    }

    private fun startAdvertising() {
        connectionText = "Wachten op klant…"; refreshRoleScreen()
        nearby.startAdvertising("Rutu BBQ Bedrijf", serviceId, connectionLifecycleCallback, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { connectionText = "Rutu BBQ is klaar voor bestellingen"; refreshRoleScreen() }
            .addOnFailureListener { connectionText = "Ontvanger starten mislukt"; refreshRoleScreen() }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (role != Role.CUSTOMER || connectedEndpoint != null) return
            connectionText = "Android bedrijf gevonden • verbinden…"; refreshRoleScreen()
            nearby.requestConnection("Rutu BBQ Klant", endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {
            if (connectedEndpoint == null) { connectionText = "Android verbinding zoeken…"; refreshRoleScreen() }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionText = "Koppelen • code ${info.authenticationDigits}"; refreshRoleScreen()
            nearby.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoint = endpointId
                nearby.stopDiscovery(); nearby.stopAdvertising()
                connectionText = if (role == Role.CUSTOMER) "Verbonden met Android bedrijf ✓" else "Klant verbonden ✓"
                runOnUiThread { toast("Verbonden ✓"); refreshRoleScreen() }
            } else {
                connectedEndpoint = null; connectionText = "Koppeling mislukt"; refreshRoleScreen()
            }
        }
        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint == endpointId) connectedEndpoint = null
            connectionText = "Verbinding verbroken"
            runOnUiThread { refreshRoleScreen() }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
            try {
                val json = JSONObject(raw)
                when (json.optString("type")) {
                    "order" -> if (role == Role.BUSINESS) {
                        val order = orderFromJson(json.getJSONObject("order"))
                        Store.upsert(this@MainActivity, order); orderRoutes[order.id] = endpointId
                        runOnUiThread { toast("Nieuwe bestelling #${order.id}"); if (screen == "business") renderBusiness() }
                    }
                    "status" -> if (role == Role.CUSTOMER) {
                        val id = json.getInt("id"); val status = json.getString("status")
                        val previous = Store.all(this@MainActivity).firstOrNull { it.id == id }?.status
                        Store.status(this@MainActivity, id, status)
                        if (previous != null && previous != status) notifyOrderStatus(id, status)
                        runOnUiThread {
                            toast("Bestelling #$id: $status")
                            if (status == "Afgerond") landing()
                            else if (screen == "orders") myOrders(false)
                        }
                    }
                }
            } catch (_: Exception) { runOnUiThread { toast("Bericht kon niet worden gelezen") } }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun sendNearbyOrder(order: Order): Boolean {
        val endpoint = connectedEndpoint ?: return false
        val json = JSONObject().put("type", "order").put("order", orderToJson(order))
        nearby.sendPayload(endpoint, Payload.fromBytes(json.toString().toByteArray()))
        return true
    }

    private fun sendStatus(id: Int, status: String) {
        val endpoint = orderRoutes[id] ?: connectedEndpoint ?: return
        nearby.sendPayload(endpoint, Payload.fromBytes(JSONObject().put("type", "status").put("id", id).put("status", status).toString().toByteArray()))
    }

    private fun orderToJson(order: Order): JSONObject {
        val items = JSONObject(); order.items.forEach { (name, qty) -> items.put(name, qty) }
        return JSONObject().put("id", order.id).put("items", items).put("total", order.total).put("status", order.status)
    }

    private fun orderFromJson(json: JSONObject): Order {
        val itemJson = json.getJSONObject("items"); val items = linkedMapOf<String, Int>()
        itemJson.keys().forEach { key -> items[key] = itemJson.getInt(key) }
        return Order(json.getInt("id"), items, json.getDouble("total"), json.getString("status"))
    }

    private fun normalizedWindowsHost(raw: String): String {
        return raw.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':').trim()
    }

    private fun windowsBase() = "http://$windowsHost:8765"

    private fun httpJson(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val connection = (URL(windowsBase() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) doOutput = true
        }
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to text
    }

    private fun testWindowsConnection(rawHost: String) {
        val host = normalizedWindowsHost(rawHost)
        if (host.isBlank()) { toast("Vul het IP-adres uit de Windows bedrijfsapp in."); return }
        windowsHost = host
        getSharedPreferences("rutu_windows", Context.MODE_PRIVATE).edit().putString("host", host).apply()
        windowsText = "Verbinden met $host…"; windowsConnected = false; refreshRoleScreen()
        Thread {
            try {
                val (code, text) = httpJson("GET", "/api/health")
                val ok = code == 200 && JSONObject(text).optBoolean("ok")
                windowsConnected = ok
                windowsText = if (ok) "Windows bedrijf verbonden ✓" else "Windows verbinding mislukt"
                runOnUiThread {
                    if (ok) {
                        toast("Verbonden met Windows bedrijf ✓")
                        uiHandler.removeCallbacks(windowsPoller)
                        uiHandler.post(windowsPoller)
                    } else toast("Kan Windows bedrijf niet bereiken.")
                    refreshRoleScreen()
                }
            } catch (_: Exception) {
                windowsConnected = false; windowsText = "Windows bedrijf niet bereikbaar"
                runOnUiThread { toast("Geen verbinding. Controleer IP, wifi en Windows Firewall."); refreshRoleScreen() }
            }
        }.start()
    }

    private fun sendWindowsOrder(order: Order) {
        val payload = orderToJson(order).put("customer", customerName().ifBlank { "Android klant" })
        Thread {
            try {
                val (code, _) = httpJson("POST", "/api/orders", payload)
                if (code in 200..299) {
                    runOnUiThread { cart.clear(); toast("Bestelling #${order.id} ontvangen door Windows ✓"); myOrders(false) }
                } else {
                    Store.status(this, order.id, "Verzenden mislukt")
                    runOnUiThread { toast("Windows heeft de bestelling niet geaccepteerd."); myOrders(false) }
                }
            } catch (_: Exception) {
                windowsConnected = false; windowsText = "Windows verbinding verbroken"
                Store.status(this, order.id, "Verzenden mislukt")
                runOnUiThread { toast("Verbinding met Windows verloren."); myOrders(false) }
            }
        }.start()
    }

    private fun syncWindowsStatuses() {
        if (!windowsConnected || windowsHost.isBlank()) return
        Thread {
            try {
                val (code, text) = httpJson("GET", "/api/orders")
                if (code != 200) return@Thread
                val arr = JSONArray(text)
                var changed = false
                var completed = false
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.getInt("id")
                    val status = obj.optString("status", "Nieuw")
                    val local = Store.all(this).firstOrNull { it.id == id }
                    if (local != null && local.status != status) {
                        Store.status(this, id, status)
                        notifyOrderStatus(id, status)
                        if (status == "Afgerond") completed = true
                        changed = true
                    }
                }
                if (changed) runOnUiThread {
                    if (completed) landing()
                    else if (screen == "orders") myOrders(false)
                }
            } catch (_: Exception) {
                windowsConnected = false; windowsText = "Windows verbinding verbroken"
                runOnUiThread { if (screen == "customer" || screen == "cart" || screen == "orders") refreshRoleScreen() }
            }
        }.start()
    }

    private fun onlineUrl(action: String, extra: Map<String, String> = emptyMap()): URL {
        val query = buildList {
            add("action=" + java.net.URLEncoder.encode(action, "UTF-8"))
            extra.forEach { (k, v) -> add(java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")) }
        }.joinToString("&")
        return URL("$onlineApiBase?$query")
    }

    private fun onlineJson(method: String, action: String, body: JSONObject? = null, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        val connection = (onlineUrl(action, extra).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) doOutput = true
        }
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return code to text
    }

    private fun testOnlineConnection(silent: Boolean) {
        Thread {
            try {
                val (code, raw) = onlineJson("GET", "health")
                val ok = code == 200 && JSONObject(raw).optBoolean("ok")
                onlineAvailable = ok
                onlineText = if (ok) "Online bestellen actief • wifi/4G/5G" else "Online bestelserver niet bereikbaar"
            } catch (_: Exception) {
                onlineAvailable = false
                onlineText = "Online bestelserver niet bereikbaar"
            }
            if (!silent) runOnUiThread {
                toast(if (onlineAvailable) "Online bestellen is actief ✓" else "Geen internetverbinding met Rutu BBQ.")
                refreshRoleScreen()
            }
        }.start()
    }

    private fun openTikkie(url: String) {
        val uri = try { Uri.parse(url) } catch (_: Exception) { null }
        if (uri?.scheme != "https" || uri.host !in setOf("tikkie.me", "www.tikkie.me")) {
            toast("Deze betaallink kan niet veilig worden geopend.")
            return
        }
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Exception) { toast("Geen app gevonden om de betaallink te openen.") }
    }

    private fun sendOnlineOrder(items: LinkedHashMap<String, Int>, shownTotal: Double, delivery: Boolean = false, address: String = "", postcode: String = "", paymentMethod: String = "", paymentPhone: String = "") {
        if (items.isEmpty()) return
        onlineText = "Bestelling veilig verzenden…"
        refreshRoleScreen()
        val itemJson = JSONObject()
        items.forEach { (name, qty) -> itemJson.put(name, qty) }
        val name = customerName()
        if (name.isBlank()) {
            runOnUiThread { ensureCustomerName() }
            return
        }
        val payload = JSONObject().put("items", itemJson).put("customer", name).put("delivery", delivery)
        if (delivery) payload.put("address", address.trim()).put("postcode", normalizedPostcode(postcode)).put("payment_method", paymentMethod).put("payment_phone", if (paymentMethod == "Tikkie") paymentPhone.trim().replace(Regex("[\\s()-]"), "") else "")
        Thread {
            try {
                val (code, raw) = onlineJson("POST", "create", payload)
                val json = JSONObject(raw)
                if (code !in 200..299 || !json.optBoolean("ok")) {
                    val message = json.optString("error", "Bestelling kon niet worden geplaatst.")
                    runOnUiThread {
                        onlineText = if (code == 409) "Vandaag gesloten voor bestellingen" else "Verzenden mislukt"
                        toast(message)
                        refreshRoleScreen()
                    }
                    return@Thread
                }
                val o = json.getJSONObject("order")
                val order = Order(
                    o.getInt("id"), items, o.optDouble("total", shownTotal), o.optString("status", "Nieuw"), o.optString("tracking", ""),
                    o.optBoolean("delivery", delivery), o.optString("address", address.trim()), o.optString("postcode", normalizedPostcode(postcode)), o.optDouble("delivery_fee", 0.0),
                    o.optString("payment_method", ""), o.optString("payment_url", ""), o.optString("payment_status", "")
                )
                Store.upsert(this, order)
                ensureBackgroundOrderStatusService()
                onlineAvailable = true
                onlineText = "Online bestellen actief • bestelling ontvangen"
                runOnUiThread {
                    cart.clear()
                    deliverySelected = false
                    deliveryAddress = ""
                    deliveryPostcode = ""
                    deliveryPaymentMethod = "Cash"
                    deliveryPaymentPhone = ""
                    toast("Bestelling #${order.id} is ontvangen door Rutu BBQ ✓")
                    cartScreen()
                    if (order.paymentMethod == "Tikkie" && order.paymentUrl.isNotBlank()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Tikkie voor bestelling #${order.id}")
                            .setMessage("Je betaallink voor ${money.format(order.total)} staat klaar. Wil je nu betalen?")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Betaal via Tikkie") { _, _ -> openTikkie(order.paymentUrl) }
                            .show()
                    }
                }
            } catch (_: Exception) {
                onlineAvailable = false
                onlineText = "Online bestelserver niet bereikbaar"
                runOnUiThread { toast("Bestelling niet verzonden. Je winkelmand blijft bewaard."); refreshRoleScreen() }
            }
        }.start()
    }

    private fun addToOnlineOrder(order: Order, items: LinkedHashMap<String, Int>) {
        if (items.isEmpty() || order.trackingToken.isBlank()) return
        onlineText = "Toevoeging veilig verzenden…"
        refreshRoleScreen()
        val itemJson = JSONObject()
        items.forEach { (name, qty) -> itemJson.put(name, qty) }
        val payload = JSONObject().put("id", order.id).put("tracking", order.trackingToken).put("items", itemJson)
        Thread {
            try {
                val (code, raw) = onlineJson("POST", "add_items", payload)
                val json = JSONObject(raw)
                if (code !in 200..299 || !json.optBoolean("ok")) {
                    val message = json.optString("error", "Toevoegen aan je bestelling is niet gelukt.")
                    runOnUiThread { onlineText = "Toevoegen mislukt"; toast(message); refreshRoleScreen() }
                    return@Thread
                }
                val o = json.getJSONObject("order")
                val mergedItems = LinkedHashMap(order.items)
                items.forEach { (name, qty) -> mergedItems[name] = (mergedItems[name] ?: 0) + qty }
                val updated = Order(
                    order.id, mergedItems, o.optDouble("total", order.total), o.optString("status", order.status), order.trackingToken,
                    o.optBoolean("delivery", order.delivery), o.optString("address", order.address), o.optString("postcode", order.postcode), o.optDouble("delivery_fee", order.deliveryFee),
                    o.optString("payment_method", order.paymentMethod), o.optString("payment_url", order.paymentUrl), o.optString("payment_status", order.paymentStatus)
                )
                Store.upsert(this, updated)
                ensureBackgroundOrderStatusService()
                onlineAvailable = true
                onlineText = "Online bestellen actief • toevoeging ontvangen"
                runOnUiThread {
                    cart.clear()
                    deliverySelected = false; deliveryAddress = ""; deliveryPostcode = ""; deliveryPaymentMethod = "Cash"; deliveryPaymentPhone = ""
                    toast("Toegevoegd aan bestelling #${order.id} ✓")
                    cartScreen()
                }
            } catch (_: Exception) {
                onlineAvailable = false
                onlineText = "Online bestelserver niet bereikbaar"
                runOnUiThread { toast("Toevoeging niet verzonden. Je winkelmand blijft bewaard."); refreshRoleScreen() }
            }
        }.start()
    }

    private fun cancelOnlineOrder(order: Order) {
        if (order.trackingToken.isBlank()) {
            toast("Deze bestelling kan niet online worden geannuleerd.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Bestelling #${order.id} annuleren?")
            .setMessage("Weet je zeker dat je deze bestelling wilt annuleren?")
            .setNegativeButton("Nee", null)
            .setPositiveButton("Ja, annuleren") { _, _ ->
                Thread {
                    try {
                        val payload = JSONObject()
                            .put("id", order.id)
                            .put("tracking", order.trackingToken)
                        val (code, raw) = onlineJson("POST", "cancel", payload)
                        val json = JSONObject(raw)
                        if (code in 200..299 && json.optBoolean("ok")) {
                            Store.status(this, order.id, "Geannuleerd")
                            runOnUiThread {
                                toast("Bestelling #${order.id} is geannuleerd.")
                                when (screen) {
                                    "orders" -> myOrders(false)
                                    "cart" -> cartScreen()
                                    "customer" -> renderCustomer()
                                }
                            }
                        } else {
                            runOnUiThread { toast(json.optString("error", "Annuleren is niet gelukt.")) }
                        }
                    } catch (_: Exception) {
                        runOnUiThread { toast("Annuleren is nu niet gelukt. Probeer het opnieuw.") }
                    }
                }.start()
            }
            .show()
    }

    private fun syncOnlineStatuses() {
        val tracked = Store.all(this).filter { it.trackingToken.isNotBlank() && it.status !in finalCustomerStatuses }
        if (tracked.isEmpty()) return
        Thread {
            var changed = false
            var completed = false
            var reachedServer = false
            tracked.forEach { order ->
                try {
                    val (code, raw) = onlineJson("GET", "status", extra = mapOf("id" to order.id.toString(), "tracking" to order.trackingToken))
                    if (code == 200) {
                        reachedServer = true
                        val serverOrder = JSONObject(raw).optJSONObject("order")
                        val status = serverOrder?.optString("status").orEmpty()
                        val paymentMethod = serverOrder?.optString("payment_method", order.paymentMethod).orEmpty()
                        val paymentUrl = serverOrder?.optString("payment_url", order.paymentUrl).orEmpty()
                        val paymentStatus = serverOrder?.optString("payment_status", order.paymentStatus).orEmpty()
                        if (paymentMethod != order.paymentMethod || paymentUrl != order.paymentUrl || paymentStatus != order.paymentStatus) {
                            Store.upsert(this, order.copy(paymentMethod = paymentMethod, paymentUrl = paymentUrl, paymentStatus = paymentStatus))
                            changed = true
                        }
                        if (status == "Afgerond" && status != order.status) {
                            notifyOrderStatus(order.id, status)
                            Store.status(this, order.id, status)
                            completed = true
                            changed = true
                        } else if (status.isNotBlank() && status != order.status) {
                            Store.status(this, order.id, status)
                            notifyOrderStatus(order.id, status)
                            changed = true
                        }
                    }
                } catch (_: Exception) {}
            }
            if (reachedServer) {
                onlineAvailable = true
                onlineText = "Online verbonden • status live"
                lastStatusRefreshText = "Live bijgewerkt: " + java.text.SimpleDateFormat("HH:mm:ss", Locale("nl", "NL")).format(java.util.Date())
            }
            if (changed) runOnUiThread {
                if (completed) {
                    landing()
                } else {
                    when (screen) {
                        "landing" -> landing()
                        "orders" -> myOrders(false)
                        "cart" -> cartScreen()
                        "customer" -> renderCustomer()
                    }
                }
            }
        }.start()
    }

    private fun syncAnnouncement(refreshVisibleScreen: Boolean) {
        Thread {
            try {
                val (code, raw) = onlineJson("GET", "announcement")
                if (code == 200) {
                    val obj = JSONObject(raw).optJSONObject("announcement")
                    val next = if (obj == null) Announcement() else Announcement(
                        title = obj.optString("title", ""),
                        message = obj.optString("message", ""),
                        from = obj.optString("from", ""),
                        until = obj.optString("until", ""),
                        active = obj.optBoolean("active", false),
                        orderingBlocked = obj.optBoolean("ordering_blocked", false)
                    )
                    val changed = next != announcement
                    announcement = next
                    if (refreshVisibleScreen && changed) runOnUiThread {
                        if (screen == "landing") landing()
                        else if (screen == "customer") renderCustomer()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun announcementCard() {
        val a = announcement
        if (!a.active || (a.title.isBlank() && a.message.isBlank())) return
        val dates = when {
            a.from.isNotBlank() && a.until.isNotBlank() -> "Van " + a.from + " t/m " + a.until
            a.from.isNotBlank() -> "Vanaf " + a.from
            a.until.isNotBlank() -> "Tot en met " + a.until
            else -> ""
        }
        val body = listOf(a.message, dates).filter { it.isNotBlank() }.joinToString("\n")
        hero(if (a.title.isBlank()) "📢 Mededeling van Rutu BBQ" else "📢 " + a.title, body)
    }
    private fun cartTotal(): Double = cart.entries.sumOf { (name, qty) ->
        (products.firstOrNull { it.name == name }?.price ?: 0.0) * qty
    }

    private val finalCustomerStatuses = setOf("Afgerond", "Geannuleerd", "Uitverkocht", "Geweigerd")
    private val eatWellBannerRefresh = Runnable {
        if (screen == "landing" || screen == "customer") {
            if (screen == "landing") landing() else renderCustomer()
        }
    }

    private var eatWellBannerVisible = false
    private val eatWellBannerWatch = object : Runnable {
        override fun run() {
            if (screen != "landing" && screen != "customer") return
            val until = getSharedPreferences("rutu_customer_banner", Context.MODE_PRIVATE)
                .getLong("eat_well_until", 0L)
            val active = until > System.currentTimeMillis()
            if (active != eatWellBannerVisible) {
                if (screen == "landing") landing() else renderCustomer()
                return
            }
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private fun watchEatWellBanner() {
        uiHandler.removeCallbacks(eatWellBannerWatch)
        if (screen == "landing" || screen == "customer") {
            uiHandler.postDelayed(eatWellBannerWatch, 1000L)
        }
    }

    private fun markEatWellBanner(orderId: Int) {
        if (Store.all(this).none { it.id == orderId }) return
        getSharedPreferences("rutu_customer_banner", Context.MODE_PRIVATE)
            .edit()
            .putLong("eat_well_until", System.currentTimeMillis() + 3 * 60 * 1000L)
            .putBoolean("force_landing", true)
            .apply()
    }

    private fun showEatWellBannerIfActive() {
        val prefs = getSharedPreferences("rutu_customer_banner", Context.MODE_PRIVATE)
        val until = prefs.getLong("eat_well_until", 0L)
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0L) {
            eatWellBannerVisible = false
            if (until > 0L) prefs.edit().remove("eat_well_until").apply()
            return
        }
        eatWellBannerVisible = true
        hero("Eet smakelijk!", "Uw bestelling is afgegeven.")
        uiHandler.removeCallbacks(eatWellBannerRefresh)
        uiHandler.postDelayed(eatWellBannerRefresh, remaining + 150L)
    }

    private fun openOrders(): List<Order> = Store.all(this).filter { it.status !in finalCustomerStatuses }

    private fun openOrdersTotal(): Double = openOrders()
        .filter { it.status !in listOf("Geannuleerd", "Uitverkocht", "Geweigerd") }
        .sumOf { it.total }

    private fun openOrdersItemCount(): Int = openOrders().sumOf { order -> order.items.values.sum() }

    private fun page(showCartBar: Boolean = false, showFamilyFooter: Boolean = false) {
        uiHandler.removeCallbacks(eatWellBannerWatch)
        activeScroll?.let { scroll ->
            if (renderedScreen.isNotBlank()) scrollPositions[renderedScreen] = scroll.scrollY
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 8, 7))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(Color.rgb(9, 8, 7))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        activeScroll = scroll
        val restoreY = scrollPositions[screen] ?: 0
        renderedScreen = screen
        shell.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        if (showCartBar) {
            val newTotal = cartTotal()
            val newQty = cart.values.sum()
            val openCount = openOrders().size
            val openItems = openOrdersItemCount()
            val openTotal = openOrdersTotal()
            val label = when {
                newQty > 0 && openCount > 0 ->
                    "🛒 Winkelmand • $newQty nieuw • ${money.format(newTotal)}   |   📦 $openCount open • ${money.format(openTotal)}"
                newQty > 0 ->
                    "🛒 Winkelmand • $newQty items • ${money.format(newTotal)}"
                openCount > 0 ->
                    "📦 Openstaand • $openCount bestelling${if (openCount == 1) "" else "en"} • $openItems items • ${money.format(openTotal)}"
                else ->
                    "🛒 Winkelmand • 0 items • ${money.format(0.0)}"
            }
            shell.addView(
                Button(this).apply {
                    text = label
                    isAllCaps = false
                    minHeight = dp(62)
                    textSize = 15f
                    setTextColor(Color.rgb(20, 14, 7))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(229, 184, 92))
                    setOnClickListener { cartScreen() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(14), dp(8), dp(14), dp(14))
                }
            )
        }
        if (showFamilyFooter) {
            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(16))
                setBackgroundColor(Color.rgb(9, 8, 7))
                contentDescription = "Part of The One Family"
            }
            footer.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.the_one_logo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(8) }
            )
            footer.addView(
                TextView(this).apply {
                    text = "Part of The One Family"
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(Color.rgb(0, 167, 255)) // The One blue
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
            shell.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(shell)
        if (restoreY > 0) scroll.post { scroll.scrollTo(0, restoreY) }
    }

    private fun landing() {
        getSharedPreferences("rutu_customer_banner", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("force_landing", false)
            .apply()
        role = Role.NONE; screen = "landing"; connectedEndpoint = null
        windowsConnected = false; uiHandler.removeCallbacks(windowsPoller)
        nearby.stopAllEndpoints(); nearby.stopAdvertising(); nearby.stopDiscovery()
        page(showFamilyFooter = true); spacer(8); logoMark(); title("Welkom bij Rutu BBQ")
        centered("Made in fire. Unique taste.", 16f, Color.rgb(205, 179, 122)); spacer(34)
        showEatWellBannerIfActive()
        hero("Fire. Roots. Flavour.", "Kies je favorieten en geniet.")
        announcementCard()
        button("Bekijk het menu") { enterCustomer() }

        spacer(20)
        watchEatWellBanner()
    }

    private fun enterCustomer() {
        if (customerName().isBlank()) ensureCustomerName()
        role = Role.CUSTOMER
        ensureNotificationPermission()
        testOnlineConnection(false)
        uiHandler.removeCallbacks(onlinePoller)
        uiHandler.post(onlinePoller)
        renderCustomer()
    }
    private fun enterBusiness() { role = Role.BUSINESS; renderBusiness(); ensureConnection() }

    private fun refreshRoleScreen() = runOnUiThread {
        when {
            role == Role.CUSTOMER && screen == "customer" -> renderCustomer()
            role == Role.CUSTOMER && screen == "cart" -> cartScreen()
            role == Role.CUSTOMER && screen == "orders" -> myOrders(false)
            role == Role.BUSINESS && screen == "business" -> renderBusiness()
        }
    }

    private fun connectionCard() {
        if (role == Role.CUSTOMER) {
            label(
                if (onlineAvailable) "🟢 $onlineText" else "🟠 $onlineText",
                if (onlineAvailable) Color.rgb(29, 38, 30) else Color.rgb(44, 34, 19)
            )
            return
        }
        when {
            windowsConnected -> label("🟢 $windowsText • $windowsHost:8765", Color.rgb(29, 38, 30))
            connectedEndpoint != null -> label("🟢 $connectionText", Color.rgb(29, 38, 30))
            else -> label("🟠 $windowsText", Color.rgb(44, 34, 19))
        }
    }

    private fun renderCustomer() {
        screen = "customer"; page(true); back { landing() }; logoMark(true); title("Ons menu"); connectionCard()
        section("Online bestellen")
        centered("Je bestelling gaat via internet naar Rutu BBQ. Hetzelfde wifi-netwerk is niet nodig.", 14f, Color.rgb(210, 199, 182))
        button("Internetverbinding opnieuw controleren", secondary = true) { testOnlineConnection(false) }
        announcementCard()
        showEatWellBannerIfActive()
        if (announcement.orderingBlocked) {
            hero("Vandaag gesloten voor bestellingen", "Je kunt het menu bekijken, maar vandaag geen bestelling plaatsen.")
        }
        hero("Van het vuur. Voor jou.", "Kies je favorieten. Met aandacht bereid, vers van het vuur.")
        products.groupBy { it.category }.forEach { (category, items) ->
            section(category)
            items.forEach { p ->
                val qty = cart[p.name] ?: 0
                card("${p.name}\n${p.description}\n${money.format(p.price)}${if (qty > 0) "   •   $qty× in mand" else ""}") {
                    button("+ Toevoegen") { cart[p.name] = qty + 1; renderCustomer() }
                }
            }
        }
        button("🧾 Mijn bestellingen", secondary = true) { myOrders() }
        watchEatWellBanner()
    }

    private fun cartScreen() {
        screen = "cart"; page(true); back { renderCustomer() }; logoMark(true); title("Jouw winkelmand"); connectionCard(); announcementCard()
        val openOrders = openOrders().reversed()
        if (openOrders.isNotEmpty()) {
            section("Openstaande bestellingen")
            openOrders.forEach { orderView(it, false) }
            section("Openstaand totaal  ${money.format(openOrdersTotal())}")
        }
        if (cart.isEmpty()) {
            if (openOrders.isEmpty()) hero("Je winkelmand is leeg", "Voeg eerst iets lekkers toe.")
            else hero("Geen nieuwe items", "Je openstaande bestelling${if (openOrders.size == 1) "" else "en"} blijft hierboven zichtbaar totdat deze is afgerond.")
            return
        }
        var total = 0.0
        cart.toMap().forEach { (name, qty) ->
            val p = products.first { it.name == name }; total += p.price * qty
            card("$qty× $name\n${money.format(p.price * qty)}") {
                smallButton("−") { if (qty <= 1) cart.remove(name) else cart[name] = qty - 1; cartScreen() }
                smallButton("+") { cart[name] = qty + 1; cartScreen() }
            }
        }
        val productTotal = total
        val addTarget = if (announcement.orderingBlocked) openOrders.firstOrNull { it.trackingToken.isNotBlank() } else null
        if (announcement.orderingBlocked) {
            if (addTarget == null) {
                hero("Bestellen is vandaag gesloten", "Nieuwe bestellingen zijn geblokkeerd. Je winkelmand blijft bewaard.")
                return
            }
            hero("Toevoegen aan bestelling #${addTarget.id}", "Omdat je al een openstaande bestelling hebt, mag je hier nog producten aan toevoegen. Er wordt geen nieuwe bestelling aangemaakt.")
            section("Toevoeging  ${money.format(productTotal)}")
            button("Toevoegen aan bestelling #${addTarget.id}") { addToOnlineOrder(addTarget, LinkedHashMap(cart)) }
            return
        }
        val deliveryCheck = CheckBox(this).apply {
            text = "Bezorgen"
            textSize = 17f
            setTextColor(Color.WHITE)
            isChecked = deliverySelected
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnCheckedChangeListener { _, checked ->
                deliverySelected = checked
                if (!checked) {
                    deliveryAddress = ""
                    deliveryPostcode = ""
                    deliveryPaymentMethod = "Cash"
                    deliveryPaymentPhone = ""
                }
                cartScreen()
            }
        }
        root.addView(deliveryCheck, marginParams(0, 8, 0, 8))

        var shownTotal = productTotal
        if (deliverySelected) {
            val addressInput = EditText(this).apply {
                hint = "Straat + huisnummer"
                setText(deliveryAddress)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(160, 150, 138))
                setSingleLine(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.rgb(23, 20, 15), Color.rgb(91, 69, 34))
            }
            val postcodeInput = EditText(this).apply {
                hint = "Postcode, bijvoorbeeld 1106 AB"
                setText(deliveryPostcode)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(160, 150, 138))
                setSingleLine(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.rgb(23, 20, 15), Color.rgb(91, 69, 34))
            }
            root.addView(addressInput, marginParams(0, 0, 0, 8))
            root.addView(postcodeInput, marginParams(0, 0, 0, 8))
            section("Betaalwijze bij bezorgen")
            val paymentOptions = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val cashOption = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "Cash"
                setTextColor(Color.WHITE)
                textSize = 17f
            }
            val tikkieOption = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "Tikkie"
                setTextColor(Color.WHITE)
                textSize = 17f
            }
            paymentOptions.addView(cashOption)
            paymentOptions.addView(tikkieOption)
            paymentOptions.check(if (deliveryPaymentMethod == "Tikkie") tikkieOption.id else cashOption.id)
            paymentOptions.setOnCheckedChangeListener { _, selected ->
                deliveryPaymentMethod = if (selected == tikkieOption.id) "Tikkie" else "Cash"
                if (deliveryPaymentMethod != "Tikkie") deliveryPaymentPhone = ""
                cartScreen()
            }
            root.addView(paymentOptions, marginParams(0, 0, 0, 8))
            if (deliveryPaymentMethod == "Tikkie") {
                val phoneInput = EditText(this).apply {
                    hint = "Mobiel nummer voor Tikkie (06 of +316)"
                    inputType = InputType.TYPE_CLASS_PHONE
                    setText(deliveryPaymentPhone)
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.rgb(160, 150, 138))
                    setSingleLine(true)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(Color.rgb(23, 20, 15), Color.rgb(91, 69, 34))
                }
                phoneInput.doAfterTextChanged { deliveryPaymentPhone = it?.toString().orEmpty() }
                root.addView(phoneInput, marginParams(0, 0, 0, 8))
            }
            val feeText = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(210, 199, 182)); setPadding(dp(8), dp(4), dp(8), dp(4)) }
            val totalText = TextView(this).apply { typeface = Typeface.create("serif", Typeface.BOLD); textSize = 22f; setTextColor(Color.rgb(244, 213, 147)); setPadding(0, dp(18), 0, dp(10)) }
            root.addView(feeText)
            root.addView(totalText)

            fun refreshDeliveryTotals() {
                val valid = validPostcode(deliveryPostcode)
                val fee = if (valid) deliveryFeeFor(deliveryPostcode) else 0.0
                shownTotal = productTotal + fee
                feeText.text = if (valid) "Bezorgkosten ${money.format(fee)} • postcode ${displayPostcode(deliveryPostcode)}" else "Bezorgkosten: ${money.format(2.50)} binnen 1106 • ${money.format(5.00)} daarbuiten"
                totalText.text = "Totaal  ${money.format(shownTotal)}"
            }
            addressInput.doAfterTextChanged { deliveryAddress = it?.toString().orEmpty() }
            postcodeInput.doAfterTextChanged {
                deliveryPostcode = it?.toString().orEmpty()
                refreshDeliveryTotals()
            }
            refreshDeliveryTotals()
        } else {
            section("Totaal  ${money.format(productTotal)}")
        }

        if (announcement.orderingBlocked) {
            hero("Bestellen is vandaag gesloten", "De gekozen kalenderdatum blokkeert bestellingen. Je winkelmand blijft bewaard.")
        } else {
            button("Bestelling plaatsen") {
                if (deliverySelected) {
                    if (deliveryAddress.trim().length < 3) {
                        toast("Vul je straat en huisnummer in.")
                        return@button
                    }
                    if (!validPostcode(deliveryPostcode)) {
                        toast("Vul een volledige postcode in, bijvoorbeeld 1106 AB.")
                        return@button
                    }
                    if (deliveryPaymentMethod == "Tikkie") {
                        val phone = deliveryPaymentPhone.replace(Regex("[\\s()-]"), "")
                        if (!Regex("^(?:06\\d{8}|\\+316\\d{8}|00316\\d{8})$").matches(phone)) {
                            toast("Vul voor Tikkie een geldig Nederlands mobiel nummer in.")
                            return@button
                        }
                    }
                    shownTotal = productTotal + deliveryFeeFor(deliveryPostcode)
                }
                sendOnlineOrder(LinkedHashMap(cart), shownTotal, deliverySelected, deliveryAddress, deliveryPostcode,
                    if (deliverySelected) deliveryPaymentMethod else "", if (deliverySelected && deliveryPaymentMethod == "Tikkie") deliveryPaymentPhone else "")
            }
        }
    }

    private fun myOrders(sync: Boolean = true) {
        screen = "orders"; page(true); back { renderCustomer() }; logoMark(true); title("Mijn bestellingen"); connectionCard(); announcementCard()
        if (sync) syncOnlineStatuses()
        label("🔄 " + lastStatusRefreshText + " • automatisch elke 2 seconden", Color.rgb(24, 31, 25))

        val allOrders = Store.all(this).reversed()
        val open = allOrders.filter { it.status !in finalCustomerStatuses }
        val history = allOrders.filter { it.status in finalCustomerStatuses }

        section("Openstaande bestellingen")
        if (open.isEmpty()) centered("Geen openstaande bestellingen.", 15f, Color.rgb(210, 199, 182))
        open.forEach { orderView(it, false) }

        section("Bestelgeschiedenis")
        if (history.isEmpty()) {
            centered("Je bestelgeschiedenis is leeg.", 15f, Color.rgb(210, 199, 182))
        } else {
            history.forEach { orderView(it, false) }
            button("Wis bestelgeschiedenis", secondary = true) {
                AlertDialog.Builder(this)
                    .setTitle("Bestelgeschiedenis wissen?")
                    .setMessage("Alleen afgesloten bestellingen worden verwijderd. Openstaande bestellingen blijven staan.")
                    .setNegativeButton("Annuleren", null)
                    .setPositiveButton("Wissen") { _, _ ->
                        Store.clearCustomerHistory(this)
                        myOrders(false)
                    }
                    .show()
            }
        }
    }

    private fun renderBusiness() {
        screen = "business"; page(); back { landing() }; logoMark(true); title("Jouw keuken"); connectionCard()
        val orders = Store.all(this).reversed()
        hero("${orders.count { it.status == "Nieuw" }} nieuwe bestellingen", "Deze Android bedrijfsmodus blijft beschikbaar voor Android-naar-Android tests.")
        if (orders.isEmpty()) centered("Wachten op de eerste bestelling…", 15f, Color.rgb(210, 199, 182))
        orders.forEach { orderView(it, true) }
        button("Verversen", secondary = true) { renderBusiness() }
        if (connectedEndpoint == null) button("Ontvanger opnieuw starten", secondary = true) { ensureConnection() }
    }

    private fun orderView(o: Order, admin: Boolean) {
        val deliveryLine = if (o.delivery) "\nBezorgen: ${o.address}, ${displayPostcode(o.postcode)} • ${money.format(o.deliveryFee)}" else "\nAfhalen"
        val paymentLine = if (o.paymentMethod == "Tikkie") "\nTikkie: " + when (o.paymentStatus) {
            "Betaald" -> "Betaald ✓"
            "Openstaand" -> "Nog niet betaald"
            "Verlopen" -> "Verlopen"
            "Controle nodig" -> "Betaallink kon niet worden bevestigd; neem contact op met Rutu BBQ"
            else -> "Automatische betaalfunctie nog niet geactiveerd"
        } else ""
        card("#${o.id}   •   ${money.format(o.total)}\n${o.items.entries.joinToString("  •  ") { "${it.value}× ${it.key}" }}$deliveryLine$paymentLine\nStatus: ${o.status}") {
            if (!admin && o.paymentMethod == "Tikkie" && o.paymentUrl.isNotBlank() && o.paymentStatus != "Betaald") {
                smallButton("Betaal via Tikkie") { openTikkie(o.paymentUrl) }
            }
            if (admin) when (o.status) {
                "Nieuw" -> { smallButton("Accepteren") { changeStatus(o, "In bereiding") }; smallButton("Weigeren") { changeStatus(o, "Geweigerd") } }
                "In bereiding" -> smallButton("Klaar") { changeStatus(o, "Klaar") }
                "Klaar" -> if (o.delivery) smallButton("Bestelling is onderweg") { changeStatus(o, "Bestelling is onderweg") } else smallButton("Afronden") { changeStatus(o, "Afgerond") }
                "Bestelling is onderweg", "Geweigerd", "Geannuleerd", "Uitverkocht" -> smallButton("Afronden") { changeStatus(o, "Afgerond") }
            } else if (o.status !in listOf("Afgerond", "Geannuleerd", "Uitverkocht", "Geweigerd")) {
                smallButton("Bestelling annuleren") { cancelOnlineOrder(o) }
            }
        }
    }

    private fun changeStatus(o: Order, status: String) { Store.status(this, o.id, status); sendStatus(o.id, status); renderBusiness() }

    private fun logoMark(compact: Boolean = false) {
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.rutu_logo)
            contentDescription = "Rutu BBQ — gouden levensboom met vlammen"
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 90 else 260)))
    }

    private fun hero(head: String, body: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = rounded(Color.rgb(28, 22, 15), Color.rgb(91, 69, 34)) }
        box.addView(TextView(this).apply { text = head; textSize = 21f; setTextColor(Color.rgb(244, 213, 147)) })
        box.addView(TextView(this).apply { text = body; textSize = 14f; setTextColor(Color.rgb(210, 199, 182)); setPadding(0, dp(7), 0, 0) })
        root.addView(box, marginParams(0, 0, 0, 14))
    }

    private fun card(text: String, actions: LinearLayout.() -> Unit = {}) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(Color.rgb(23, 20, 15), Color.rgb(70, 56, 32)) }
        box.addView(TextView(this).apply { this.text = text; textSize = 16f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.15f) })
        box.actions(); root.addView(box, marginParams(0, 0, 0, 10))
    }

    private fun title(s: String) { root.addView(TextView(this).apply { text = s; typeface = Typeface.create("serif", Typeface.BOLD); textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.rgb(244, 213, 147)); setPadding(0, dp(12), 0, dp(22)) }) }
    private fun section(s: String) { root.addView(TextView(this).apply { text = s; typeface = Typeface.create("serif", Typeface.BOLD); textSize = 22f; setTextColor(Color.rgb(244, 213, 147)); setPadding(0, dp(18), 0, dp(10)) }) }
    private fun centered(s: String, size: Float, color: Int) { root.addView(TextView(this).apply { text = s; textSize = size; gravity = Gravity.CENTER; setTextColor(color) }) }
    private fun label(s: String, bg: Int = Color.rgb(23, 20, 15)) { root.addView(TextView(this).apply { text = s; textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(bg, Color.rgb(48, 48, 55)) }, marginParams(0, 0, 0, 12)) }
    private fun button(s: String, secondary: Boolean = false, action: () -> Unit) { root.addView(Button(this).apply { text = s; isAllCaps = false; minHeight = dp(52); textSize = 16f; setTextColor(if (secondary) Color.WHITE else Color.rgb(20, 14, 7)); backgroundTintList = android.content.res.ColorStateList.valueOf(if (secondary) Color.rgb(36, 32, 25) else Color.rgb(229, 184, 92)); setOnClickListener { action() } }, marginParams(0, 10, 0, 0)) }
    private fun LinearLayout.smallButton(s: String, action: () -> Unit) { addView(Button(this@MainActivity).apply { text = s; isAllCaps = false; minHeight = dp(48); setTextColor(Color.rgb(20, 14, 7)); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(229, 184, 92)); setOnClickListener { action() } }, marginParams(0, 8, 0, 0)) }
    private fun back(action: () -> Unit) { root.addView(Button(this).apply { text = "← Terug"; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(36, 32, 25)); setOnClickListener { action() } }, marginParams(0, 0, 0, 8)) }
    private fun spacer(h: Int) { root.addView(TextView(this), LinearLayout.LayoutParams(1, dp(h))) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun marginParams(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun rounded(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat(); setColor(fill); setStroke(dp(1), stroke) }

    object Store {
        private const val FILE = "rutu_orders"
        private const val KEY = "orders"
        fun all(c: Context): MutableList<Order> {
            val raw = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
            if (raw.isBlank()) return mutableListOf()
            return raw.split("§").mapNotNull { row ->
                try {
                    val parts = row.split("¦"); val items = linkedMapOf<String, Int>()
                    if (parts.getOrNull(3).orEmpty().isNotBlank()) parts[3].split("~").forEach { pair -> val p = pair.split("="); if (p.size == 2) items[p[0]] = p[1].toInt() }
                    Order(
                        parts[0].toInt(), items, parts[1].toDouble(), parts[2], parts.getOrNull(4).orEmpty(),
                        parts.getOrNull(5) == "1", parts.getOrNull(6).orEmpty(), parts.getOrNull(7).orEmpty(), parts.getOrNull(8)?.toDoubleOrNull() ?: 0.0,
                        parts.getOrNull(9).orEmpty(), parts.getOrNull(10).orEmpty(), parts.getOrNull(11).orEmpty()
                    )
                } catch (_: Exception) { null }
            }.toMutableList()
        }
        private fun save(c: Context, orders: List<Order>) {
            val raw = orders.joinToString("§") { o ->
                "${o.id}¦${o.total}¦${o.status}¦${o.items.entries.joinToString("~") { "${it.key}=${it.value}" }}¦${o.trackingToken}¦${if (o.delivery) "1" else "0"}¦${o.address.replace("¦", " ")}¦${o.postcode.replace("¦", " ")}¦${o.deliveryFee}¦${o.paymentMethod}¦${o.paymentUrl.replace("¦", "").replace("§", "")}¦${o.paymentStatus}"
            }
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
        }
        fun create(c: Context, items: Map<String, Int>, total: Double): Order {
            val orders = all(c); val id = maxOf(1045, orders.maxOfOrNull { it.id } ?: 1045) + 1
            val o = Order(id, LinkedHashMap(items), total, "Nieuw"); orders += o; save(c, orders); return o
        }
        fun upsert(c: Context, order: Order) { val orders = all(c); val i = orders.indexOfFirst { it.id == order.id }; if (i >= 0) orders[i] = order else orders += order; save(c, orders) }
        fun status(c: Context, id: Int, status: String) { val orders = all(c); orders.firstOrNull { it.id == id }?.status = status; save(c, orders) }
        fun remove(c: Context, id: Int) { val orders = all(c); orders.removeAll { it.id == id }; save(c, orders) }
        fun clearCustomerHistory(c: Context) {
            val finalStatuses = setOf("Afgerond", "Geannuleerd", "Uitverkocht", "Geweigerd")
            val orders = all(c)
            if (orders.removeAll { it.status in finalStatuses }) save(c, orders)
        }
    }
}