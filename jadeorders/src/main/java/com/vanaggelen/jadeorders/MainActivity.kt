package com.vanaggelen.jadeorders

import android.Manifest
import android.content.Context
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    data class Product(val name: String, val price: Double, val category: String)
    data class Order(val id: Int, val items: LinkedHashMap<String, Int>, val total: Double, var status: String, val trackingToken: String = "")
    data class Announcement(val title: String = "", val message: String = "", val from: String = "", val until: String = "", val active: Boolean = false, val orderingBlocked: Boolean = false)

    private val products = listOf(
        Product("Teriyaki Chicken", 12.50, "BBQ"),
        Product("The Emperor Burger", 14.95, "BBQ"),
        Product("Nasi Special", 11.50, "Meals"),
        Product("Roti Kip", 13.50, "Meals"),
        Product("Friet groot", 4.25, "Sides"),
        Product("Ube Cheesecake", 6.95, "Dessert"),
        Product("Cola", 2.75, "Drinks"),
        Product("Iced Tea", 2.75, "Drinks")
    )
    private val cart = linkedMapOf<String, Int>()
    private val orderRoutes = mutableMapOf<Int, String>()
    private lateinit var root: LinearLayout
    private lateinit var nearby: ConnectionsClient
    private val money = NumberFormat.getCurrencyInstance(Locale("nl", "NL"))
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val permissionRequest = 4041
    private var role = Role.NONE
    private var connectedEndpoint: String? = null
    private var connectionText = "Niet verbonden"
    private var pendingStart = false
    private var screen = "landing"
    private val serviceId by lazy { "$packageName.rutubbq.v1" }

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
        windowsHost = getSharedPreferences("rutu_windows", Context.MODE_PRIVATE).getString("host", "") ?: ""
        landing()
        syncAnnouncement(true)
        RutuUpdateChecker.checkForUpdate(this)
    }

    override fun onResume() {
        super.onResume()
        syncAnnouncement(true)
        if (role == Role.CUSTOMER) {
            uiHandler.removeCallbacks(onlinePoller)
            uiHandler.post(onlinePoller)
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(windowsPoller)
        uiHandler.removeCallbacks(onlinePoller)
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
                        Store.status(this@MainActivity, id, status)
                        runOnUiThread { toast("Bestelling #$id: $status"); if (screen == "orders") myOrders(false) }
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
        val payload = orderToJson(order).put("customer", "Android klant")
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
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.getInt("id")
                    val status = obj.optString("status", "Nieuw")
                    val local = Store.all(this).firstOrNull { it.id == id }
                    if (local != null && local.status != status) {
                        Store.status(this, id, status); changed = true
                    }
                }
                if (changed && screen == "orders") runOnUiThread { myOrders(false) }
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

    private fun sendOnlineOrder(items: LinkedHashMap<String, Int>, shownTotal: Double) {
        if (items.isEmpty()) return
        onlineText = "Bestelling veilig verzenden…"
        refreshRoleScreen()
        val itemJson = JSONObject()
        items.forEach { (name, qty) -> itemJson.put(name, qty) }
        val payload = JSONObject().put("items", itemJson).put("customer", "Android klant")
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
                val order = Order(o.getInt("id"), items, o.optDouble("total", shownTotal), o.optString("status", "Nieuw"), o.optString("tracking", ""))
                Store.upsert(this, order)
                onlineAvailable = true
                onlineText = "Online bestellen actief • bestelling ontvangen"
                runOnUiThread {
                    cart.clear()
                    toast("Bestelling #${order.id} is ontvangen door Rutu BBQ ✓")
                    myOrders(false)
                }
            } catch (_: Exception) {
                onlineAvailable = false
                onlineText = "Online bestelserver niet bereikbaar"
                runOnUiThread { toast("Bestelling niet verzonden. Je winkelmand blijft bewaard."); refreshRoleScreen() }
            }
        }.start()
    }

    private fun syncOnlineStatuses() {
        val tracked = Store.all(this).filter { it.trackingToken.isNotBlank() }
        if (tracked.isEmpty()) return
        Thread {
            var changed = false
            var reachedServer = false
            tracked.forEach { order ->
                try {
                    val (code, raw) = onlineJson("GET", "status", extra = mapOf("id" to order.id.toString(), "tracking" to order.trackingToken))
                    if (code == 200) {
                        reachedServer = true
                        val status = JSONObject(raw).optJSONObject("order")?.optString("status").orEmpty()
                        if (status == "Afgerond") {
                            Store.remove(this, order.id)
                            changed = true
                        } else if (status.isNotBlank() && status != order.status) {
                            Store.status(this, order.id, status)
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
            if (screen == "orders" && (changed || reachedServer)) runOnUiThread { myOrders(false) }
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

    private fun page(showCartBar: Boolean = false) {
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
        shell.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        if (showCartBar) {
            val total = cartTotal()
            val qty = cart.values.sum()
            shell.addView(
                Button(this).apply {
                    text = "🛒 Winkelmand  •  $qty items  •  ${money.format(total)}"
                    isAllCaps = false
                    minHeight = dp(62)
                    textSize = 17f
                    setTextColor(Color.rgb(20, 14, 7))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(229, 184, 92))
                    setOnClickListener { cartScreen() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(14), dp(8), dp(14), dp(14))
                }
            )
        }
        setContentView(shell)
    }

    private fun landing() {
        role = Role.NONE; screen = "landing"; connectedEndpoint = null
        windowsConnected = false; uiHandler.removeCallbacks(windowsPoller)
        nearby.stopAllEndpoints(); nearby.stopAdvertising(); nearby.stopDiscovery()
        page(); spacer(8); logoMark(); title("Welkom bij Rutu BBQ")
        centered("More than food. It’s an experience.", 16f, Color.rgb(205, 179, 122)); spacer(34)
        hero("Fire. Roots. Flavour.", "Gemaakt in vuur. Unieke smaak. Kies je favorieten en geniet.")
        announcementCard()
        button("Bekijk het menu") { enterCustomer() }

        spacer(20); centered("Android • Rutu BBQ", 12f, Color.rgb(189, 178, 161))
    }

    private fun enterCustomer() {
        role = Role.CUSTOMER
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
        if (announcement.orderingBlocked) {
            hero("Vandaag gesloten voor bestellingen", "Je kunt het menu bekijken, maar vandaag geen bestelling plaatsen.")
        }
        hero("Van het vuur. Voor jou.", "Kies je favorieten. Met aandacht bereid, vers van het vuur.")
        products.groupBy { it.category }.forEach { (category, items) ->
            section(category)
            items.forEach { p ->
                val qty = cart[p.name] ?: 0
                card("${p.name}\n${money.format(p.price)}${if (qty > 0) "   •   $qty× in mand" else ""}") {
                    button("+ Toevoegen") { cart[p.name] = qty + 1; renderCustomer() }
                }
            }
        }
        button("🧾 Mijn bestellingen", secondary = true) { myOrders() }
    }

    private fun cartScreen() {
        screen = "cart"; page(true); back { renderCustomer() }; logoMark(true); title("Jouw winkelmand"); connectionCard(); announcementCard()
        if (cart.isEmpty()) { hero("Je winkelmand is leeg", "Voeg eerst iets lekkers toe."); return }
        var total = 0.0
        cart.toMap().forEach { (name, qty) ->
            val p = products.first { it.name == name }; total += p.price * qty
            card("$qty× $name\n${money.format(p.price * qty)}") {
                smallButton("−") { if (qty <= 1) cart.remove(name) else cart[name] = qty - 1; cartScreen() }
                smallButton("+") { cart[name] = qty + 1; cartScreen() }
            }
        }
        section("Totaal  ${money.format(total)}")
        if (announcement.orderingBlocked) {
            hero("Bestellen is vandaag gesloten", "De gekozen kalenderdatum blokkeert bestellingen. Je winkelmand blijft bewaard.")
        } else {
            button("Bestelling plaatsen") {
                sendOnlineOrder(LinkedHashMap(cart), total)
            }
        }
    }

    private fun myOrders(sync: Boolean = true) {
        screen = "orders"; Store.removeCompleted(this); page(true); back { renderCustomer() }; logoMark(true); title("Mijn bestellingen"); connectionCard(); announcementCard()
        if (sync) syncOnlineStatuses()
        label("🔄 " + lastStatusRefreshText + " • automatisch elke 2 seconden", Color.rgb(24, 31, 25))
        val orders = Store.all(this).reversed()
        if (orders.isEmpty()) hero("Nog geen bestellingen", "Je geplaatste bestellingen verschijnen hier.")
        orders.forEach { orderView(it, false) }
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
        card("#${o.id}   •   ${money.format(o.total)}\n${o.items.entries.joinToString("  •  ") { "${it.value}× ${it.key}" }}\nStatus: ${o.status}") {
            if (admin) when (o.status) {
                "Nieuw" -> { smallButton("Accepteren") { changeStatus(o, "In bereiding") }; smallButton("Weigeren") { changeStatus(o, "Geweigerd") } }
                "In bereiding" -> smallButton("Klaar") { changeStatus(o, "Klaar") }
                "Klaar" -> smallButton("Afronden") { changeStatus(o, "Afgerond") }
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
                    Order(parts[0].toInt(), items, parts[1].toDouble(), parts[2], parts.getOrNull(4).orEmpty())
                } catch (_: Exception) { null }
            }.toMutableList()
        }
        private fun save(c: Context, orders: List<Order>) {
            val raw = orders.joinToString("§") { o -> "${o.id}¦${o.total}¦${o.status}¦${o.items.entries.joinToString("~") { "${it.key}=${it.value}" }}¦${o.trackingToken}" }
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
        }
        fun create(c: Context, items: Map<String, Int>, total: Double): Order {
            val orders = all(c); val id = maxOf(1045, orders.maxOfOrNull { it.id } ?: 1045) + 1
            val o = Order(id, LinkedHashMap(items), total, "Nieuw"); orders += o; save(c, orders); return o
        }
        fun upsert(c: Context, order: Order) { val orders = all(c); val i = orders.indexOfFirst { it.id == order.id }; if (i >= 0) orders[i] = order else orders += order; save(c, orders) }
        fun status(c: Context, id: Int, status: String) { val orders = all(c); orders.firstOrNull { it.id == id }?.status = status; save(c, orders) }
        fun remove(c: Context, id: Int) { val orders = all(c); orders.removeAll { it.id == id }; save(c, orders) }
        fun removeCompleted(c: Context) { val orders = all(c); if (orders.removeAll { it.status == "Afgerond" }) save(c, orders) }
    }
}