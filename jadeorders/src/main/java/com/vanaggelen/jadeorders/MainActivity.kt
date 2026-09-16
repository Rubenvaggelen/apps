package com.vanaggelen.jadeorders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private enum class Role { NONE, CUSTOMER, BUSINESS }

    private val products = listOf(
        Product("Teriyaki Chicken", 12.50), Product("The Emperor Burger", 14.95),
        Product("Ube Cheesecake", 6.95), Product("Nasi Special", 11.50),
        Product("Roti Kip", 13.50), Product("Friet groot", 4.25),
        Product("Cola", 2.75), Product("Iced Tea", 2.75)
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

    private val serviceId: String by lazy { "$packageName.jadeorders.v1" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearby = Nearby.getConnectionsClient(this)
        landing()
    }

    override fun onDestroy() {
        nearby.stopAllEndpoints()
        nearby.stopAdvertising()
        nearby.stopDiscovery()
        super.onDestroy()
    }

    private fun permissionsNeeded(): Array<String> {
        val result = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            result += Manifest.permission.BLUETOOTH_SCAN
            result += Manifest.permission.BLUETOOTH_CONNECT
            result += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) {
            result += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            result += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return result.toTypedArray()
    }

    private fun hasPermissions(): Boolean = permissionsNeeded().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsThenStart() {
        if (hasPermissions()) {
            startNearbyForRole()
        } else {
            pendingStart = true
            ActivityCompat.requestPermissions(this, permissionsNeeded(), permissionRequest)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && pendingStart) {
            pendingStart = false
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startNearbyForRole()
            } else {
                Toast.makeText(this, "Toestemming is nodig om de twee telefoons te koppelen.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startNearbyForRole() {
        when (role) {
            Role.CUSTOMER -> startDiscovery()
            Role.BUSINESS -> startAdvertising()
            else -> Unit
        }
    }

    private fun startDiscovery() {
        connectionText = "Zoeken naar ontvangende telefoon…"
        refreshRoleScreen()
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        nearby.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                connectionText = "Zoeken naar bedrijf…"
                refreshRoleScreen()
            }
            .addOnFailureListener {
                connectionText = "Zoeken mislukt: ${it.message ?: "onbekende fout"}"
                refreshRoleScreen()
            }
    }

    private fun startAdvertising() {
        connectionText = "Wachten op klanttelefoon…"
        refreshRoleScreen()
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        nearby.startAdvertising("Jade Orders Bedrijf", serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                connectionText = "Ontvanger actief • wacht op klant"
                refreshRoleScreen()
            }
            .addOnFailureListener {
                connectionText = "Ontvanger starten mislukt: ${it.message ?: "onbekende fout"}"
                refreshRoleScreen()
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (role != Role.CUSTOMER || connectedEndpoint != null) return
            connectionText = "Bedrijf gevonden • verbinden…"
            refreshRoleScreen()
            nearby.requestConnection("Jade Orders Klant", endpointId, connectionLifecycleCallback)
                .addOnFailureListener {
                    connectionText = "Verbinden mislukt: ${it.message ?: "onbekende fout"}"
                    refreshRoleScreen()
                }
        }

        override fun onEndpointLost(endpointId: String) {
            if (connectedEndpoint == null) {
                connectionText = "Bedrijf niet meer gevonden • opnieuw zoeken…"
                refreshRoleScreen()
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionText = "Koppelen • code ${info.authenticationDigits}"
            refreshRoleScreen()
            nearby.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoint = endpointId
                nearby.stopDiscovery()
                nearby.stopAdvertising()
                connectionText = if (role == Role.CUSTOMER) "Verbonden met bedrijf ✓" else "Klant verbonden ✓"
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Telefoons zijn gekoppeld", Toast.LENGTH_LONG).show()
                    refreshRoleScreen()
                }
            } else {
                connectedEndpoint = null
                connectionText = "Koppeling geweigerd/mislukt (${resolution.status.statusCode})"
                refreshRoleScreen()
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint == endpointId) connectedEndpoint = null
            connectionText = "Verbinding verbroken"
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Verbinding met andere telefoon verbroken", Toast.LENGTH_LONG).show()
                refreshRoleScreen()
            }
            if (hasPermissions()) startNearbyForRole()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val raw = String(bytes, Charsets.UTF_8)
            try {
                val json = JSONObject(raw)
                when (json.optString("type")) {
                    "order" -> {
                        if (role != Role.BUSINESS) return
                        val order = orderFromJson(json.getJSONObject("order"))
                        Store.upsert(this@MainActivity, order)
                        orderRoutes[order.id] = endpointId
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "NIEUWE BESTELLING #${order.id}", Toast.LENGTH_LONG).show()
                            if (screen == "business") renderBusiness()
                        }
                    }
                    "status" -> {
                        if (role != Role.CUSTOMER) return
                        val id = json.getInt("id")
                        val status = json.getString("status")
                        Store.status(this@MainActivity, id, status)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Bestelling #$id: $status", Toast.LENGTH_LONG).show()
                            if (screen == "orders") myOrders()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Bericht kon niet worden gelezen", Toast.LENGTH_SHORT).show() }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun sendOrder(order: Order): Boolean {
        val endpoint = connectedEndpoint ?: return false
        val json = JSONObject().put("type", "order").put("order", orderToJson(order))
        nearby.sendPayload(endpoint, Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { runOnUiThread { Toast.makeText(this, "Versturen mislukt: ${it.message}", Toast.LENGTH_LONG).show() } }
        return true
    }

    private fun sendStatus(id: Int, status: String) {
        val endpoint = orderRoutes[id] ?: connectedEndpoint ?: return
        val json = JSONObject().put("type", "status").put("id", id).put("status", status)
        nearby.sendPayload(endpoint, Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun orderToJson(order: Order): JSONObject {
        val items = JSONObject()
        order.items.forEach { (name, qty) -> items.put(name, qty) }
        return JSONObject()
            .put("id", order.id)
            .put("items", items)
            .put("total", order.total)
            .put("status", order.status)
    }

    private fun orderFromJson(json: JSONObject): Order {
        val itemsJson = json.getJSONObject("items")
        val items = linkedMapOf<String, Int>()
        itemsJson.keys().forEach { key -> items[key] = itemsJson.getInt(key) }
        return Order(json.getInt("id"), items, json.getDouble("total"), json.getString("status"))
    }

    private fun page() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 40)
            setBackgroundColor(Color.rgb(11, 11, 13))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun landing() {
        role = Role.NONE
        screen = "landing"
        connectedEndpoint = null
        nearby.stopAllEndpoints(); nearby.stopAdvertising(); nearby.stopDiscovery()
        page(); title("The Jade Emperor"); text("TWEE-TELEFOON TEST • ZENDER + ONTVANGER")
        label("Gebruik exact dezelfde APK op beide telefoons.")
        button("📱 Telefoon 1 • Klant / Zender") { enterCustomer() }
        button("🏪 Telefoon 2 • Bedrijf / Ontvanger") { enterBusiness() }
        text("Zet Bluetooth en wifi op beide telefoons aan. De app koppelt de telefoons rechtstreeks in de buurt; internet is voor deze eerste test niet nodig.")
    }

    private fun enterCustomer() {
        role = Role.CUSTOMER
        screen = "customer"
        renderCustomer()
        ensurePermissionsThenStart()
    }

    private fun enterBusiness() {
        role = Role.BUSINESS
        screen = "business"
        renderBusiness()
        ensurePermissionsThenStart()
    }

    private fun refreshRoleScreen() {
        runOnUiThread {
            when {
                role == Role.CUSTOMER && screen == "customer" -> renderCustomer()
                role == Role.BUSINESS && screen == "business" -> renderBusiness()
            }
        }
    }

    private fun connectionCard() {
        label(if (connectedEndpoint != null) "🟢 $connectionText" else "🟠 $connectionText")
    }

    private fun renderCustomer() {
        screen = "customer"
        page(); back { landing() }; title("Klant • Zender"); connectionCard()
        products.forEach { p ->
            label("${p.name}  •  ${money.format(p.price)}")
            button("+ Toevoegen") { cart[p.name] = (cart[p.name] ?: 0) + 1; renderCustomer() }
        }
        button("Winkelmand (${cart.values.sum()})") { cart() }
        button("Mijn bestellingen") { myOrders() }
        if (connectedEndpoint == null) button("Opnieuw zoeken naar ontvanger") { ensurePermissionsThenStart() }
    }

    private fun cart() {
        screen = "cart"
        page(); back { renderCustomer() }; title("Winkelmand"); connectionCard()
        if (cart.isEmpty()) { text("Je winkelmand is leeg."); return }
        var total = 0.0
        cart.forEach { (name, qty) ->
            val p = products.first { it.name == name }
            total += p.price * qty
            label("$qty× $name — ${money.format(p.price * qty)}")
        }
        label("Totaal: ${money.format(total)}")
        button("Bestelling naar telefoon 2 sturen") {
            if (connectedEndpoint == null) {
                Toast.makeText(this, "Nog niet verbonden met de ontvangende telefoon.", Toast.LENGTH_LONG).show()
                return@button
            }
            val order = Store.create(this, cart, total)
            if (sendOrder(order)) {
                cart.clear()
                Toast.makeText(this, "Bestelling #${order.id} verzonden ✓", Toast.LENGTH_LONG).show()
                myOrders()
            }
        }
    }

    private fun myOrders() {
        screen = "orders"
        page(); back { renderCustomer() }; title("Mijn bestellingen"); connectionCard()
        val orders = Store.all(this).reversed()
        if (orders.isEmpty()) text("Nog geen bestellingen geplaatst.")
        orders.forEach { o -> orderView(o, false) }
        button("Verversen") { myOrders() }
    }

    private fun renderBusiness() {
        screen = "business"
        page(); back { landing() }; title("Bedrijf • Ontvanger"); connectionCard()
        val orders = Store.all(this).reversed()
        label("${orders.count { it.status == "Nieuw" }} nieuwe bestellingen")
        if (orders.isEmpty()) text("Wachten op de eerste bestelling van telefoon 1…")
        orders.forEach { o -> orderView(o, true) }
        button("Verversen") { renderBusiness() }
        if (connectedEndpoint == null) button("Ontvanger opnieuw starten") { ensurePermissionsThenStart() }
    }

    private fun changeStatus(o: Order, status: String) {
        Store.status(this, o.id, status)
        sendStatus(o.id, status)
        renderBusiness()
    }

    private fun orderView(o: Order, admin: Boolean) {
        label("#${o.id} • ${o.status} • ${money.format(o.total)}")
        text(o.items.entries.joinToString("\n") { "${it.value}× ${it.key}" })
        if (admin) when (o.status) {
            "Nieuw" -> {
                button("Accepteren") { changeStatus(o, "In bereiding") }
                button("Weigeren") { changeStatus(o, "Geweigerd") }
            }
            "In bereiding" -> button("Klaar") { changeStatus(o, "Klaar") }
            "Klaar" -> button("Afronden") { changeStatus(o, "Afgerond") }
        }
    }

    private fun title(s: String) = root.addView(TextView(this).apply { text = s; textSize = 28f; setTextColor(Color.rgb(242, 207, 122)); setPadding(0, 12, 0, 22) })
    private fun label(s: String) = root.addView(TextView(this).apply { text = s; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, 16, 0, 8) })
    private fun text(s: String) = root.addView(TextView(this).apply { text = s; textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 14) })
    private fun button(s: String, action: () -> Unit) = root.addView(Button(this).apply { text = s; isAllCaps = false; setOnClickListener { action() } })
    private fun back(action: () -> Unit) = button("← Terug", action)
}

data class Product(val name: String, val price: Double)
data class Order(val id: Int, val items: Map<String, Int>, val total: Double, var status: String)

object Store {
    private const val PREF = "jade_orders"
    private const val KEY = "orders"

    fun all(c: Context): MutableList<Order> {
        val a = JSONArray(c.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]")
        return MutableList(a.length()) { i ->
            val o = a.getJSONObject(i)
            val j = o.getJSONObject("items")
            val m = linkedMapOf<String, Int>()
            j.keys().forEach { k -> m[k] = j.getInt(k) }
            Order(o.getInt("id"), m, o.getDouble("total"), o.getString("status"))
        }
    }

    fun create(c: Context, cart: Map<String, Int>, total: Double): Order {
        val all = all(c)
        val seed = ((System.currentTimeMillis() / 1000L) % 900000L + 100000L).toInt()
        val id = maxOf(seed, (all.maxOfOrNull { it.id } ?: 0) + 1)
        val o = Order(id, LinkedHashMap(cart), total, "Nieuw")
        all.add(o); save(c, all); return o
    }

    fun upsert(c: Context, order: Order) {
        val all = all(c)
        val index = all.indexOfFirst { it.id == order.id }
        if (index >= 0) all[index] = order else all.add(order)
        save(c, all)
    }

    fun status(c: Context, id: Int, s: String) {
        val all = all(c)
        all.firstOrNull { it.id == id }?.status = s
        save(c, all)
    }

    private fun save(c: Context, all: List<Order>) {
        val a = JSONArray()
        all.forEach { o ->
            val items = JSONObject(); o.items.forEach { (k, v) -> items.put(k, v) }
            a.put(JSONObject().put("id", o.id).put("items", items).put("total", o.total).put("status", o.status))
        }
        c.getSharedPreferences(PREF, 0).edit().putString(KEY, a.toString()).apply()
    }
}
