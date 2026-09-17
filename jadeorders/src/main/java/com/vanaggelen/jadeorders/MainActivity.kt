package com.vanaggelen.jadeorders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private enum class Role { NONE, CUSTOMER, BUSINESS }
    data class Product(val name: String, val price: Double, val category: String)
    data class Order(val id: Int, val items: LinkedHashMap<String, Int>, val total: Double, var status: String)

    private val products = listOf(
        Product("Teriyaki Chicken", 12.50, "BBQ"), Product("The Emperor Burger", 14.95, "BBQ"),
        Product("Nasi Special", 11.50, "Meals"), Product("Roti Kip", 13.50, "Meals"),
        Product("Friet groot", 4.25, "Sides"), Product("Ube Cheesecake", 6.95, "Dessert"),
        Product("Cola", 2.75, "Drinks"), Product("Iced Tea", 2.75, "Drinks")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearby = Nearby.getConnectionsClient(this)
        landing()
    }

    override fun onDestroy() {
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
            else toast("Toestemming is nodig om bestellingen tussen de twee telefoons te versturen.")
        }
    }

    private fun startNearbyForRole() = when (role) {
        Role.CUSTOMER -> startDiscovery()
        Role.BUSINESS -> startAdvertising()
        else -> Unit
    }

    private fun startDiscovery() {
        connectionText = "Rutu BBQ zoeken…"; refreshRoleScreen()
        nearby.startDiscovery(serviceId, endpointDiscoveryCallback, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { connectionText = "Zoeken naar Rutu BBQ…"; refreshRoleScreen() }
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
            connectionText = "Rutu BBQ gevonden • verbinden…"; refreshRoleScreen()
            nearby.requestConnection("Rutu BBQ Klant", endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {
            if (connectedEndpoint == null) { connectionText = "Verbinding zoeken…"; refreshRoleScreen() }
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
                connectionText = if (role == Role.CUSTOMER) "Verbonden met Rutu BBQ ✓" else "Klant verbonden ✓"
                runOnUiThread { toast("Verbonden ✓"); refreshRoleScreen() }
            } else {
                connectedEndpoint = null; connectionText = "Koppeling mislukt"; refreshRoleScreen()
            }
        }
        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint == endpointId) connectedEndpoint = null
            connectionText = "Verbinding verbroken"
            runOnUiThread { refreshRoleScreen() }
            if (hasPermissions()) startNearbyForRole()
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
                        runOnUiThread { toast("Bestelling #$id: $status"); if (screen == "orders") myOrders() }
                    }
                }
            } catch (_: Exception) { runOnUiThread { toast("Bericht kon niet worden gelezen") } }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun sendOrder(order: Order): Boolean {
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

    private fun page() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(44))
            setBackgroundColor(Color.rgb(8, 8, 10))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun landing() {
        role = Role.NONE; screen = "landing"; connectedEndpoint = null
        nearby.stopAllEndpoints(); nearby.stopAdvertising(); nearby.stopDiscovery()
        page()
        spacer(36)
        logoMark()
        title("RUTU BBQ")
        centered("More than food. It’s an experience.", 16f, Color.rgb(205, 179, 122))
        spacer(34)
        hero("🔥 Fire. Roots. Flavour.", "Bestel als klant of open de bedrijfsmodus om bestellingen te ontvangen.")
        button("🔥 Bestellen") { enterCustomer() }
        button("🏪 Bedrijfsmodus", secondary = true) { enterBusiness() }
        spacer(20)
        centered("Android • Rutu BBQ", 12f, Color.GRAY)
    }

    private fun enterCustomer() { role = Role.CUSTOMER; renderCustomer(); ensureConnection() }
    private fun enterBusiness() { role = Role.BUSINESS; renderBusiness(); ensureConnection() }

    private fun refreshRoleScreen() = runOnUiThread {
        when { role == Role.CUSTOMER && screen == "customer" -> renderCustomer(); role == Role.BUSINESS && screen == "business" -> renderBusiness() }
    }

    private fun connectionCard() = label(if (connectedEndpoint != null) "🟢 $connectionText" else "🟠 $connectionText", Color.rgb(29, 38, 30))

    private fun renderCustomer() {
        screen = "customer"; page(); back { landing() }; title("Rutu BBQ • Menu"); connectionCard()
        hero("🔥 Welkom bij Rutu BBQ", "Kies je favorieten. Je bestelling wordt rechtstreeks naar het bedrijf gestuurd.")
        products.groupBy { it.category }.forEach { (category, items) ->
            section(category)
            items.forEach { p ->
                val qty = cart[p.name] ?: 0
                card("${p.name}\n${money.format(p.price)}${if (qty > 0) "   •   $qty× in mand" else ""}") {
                    button("+ Toevoegen") { cart[p.name] = qty + 1; renderCustomer() }
                }
            }
        }
        button("🛒 Winkelmand (${cart.values.sum()})") { cartScreen() }
        button("🧾 Mijn bestellingen", secondary = true) { myOrders() }
        if (connectedEndpoint == null) button("Opnieuw verbinden", secondary = true) { ensureConnection() }
    }

    private fun cartScreen() {
        screen = "cart"; page(); back { renderCustomer() }; title("Winkelmand"); connectionCard()
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
        button("🔥 Bestelling plaatsen") {
            if (connectedEndpoint == null) { toast("Nog niet verbonden met Rutu BBQ."); return@button }
            val order = Store.create(this, cart, total)
            if (sendOrder(order)) { cart.clear(); toast("Bestelling #${order.id} verzonden ✓"); myOrders() }
        }
    }

    private fun myOrders() {
        screen = "orders"; page(); back { renderCustomer() }; title("Mijn bestellingen"); connectionCard()
        val orders = Store.all(this).reversed()
        if (orders.isEmpty()) hero("Nog geen bestellingen", "Je geplaatste bestellingen verschijnen hier.")
        orders.forEach { orderView(it, false) }
        button("Verversen", secondary = true) { myOrders() }
    }

    private fun renderBusiness() {
        screen = "business"; page(); back { landing() }; title("Rutu BBQ • Bedrijf"); connectionCard()
        val orders = Store.all(this).reversed()
        hero("${orders.count { it.status == "Nieuw" }} nieuwe bestellingen", "Beheer de keukenstatus en stuur updates terug naar de klant.")
        if (orders.isEmpty()) centered("Wachten op de eerste bestelling…", 15f, Color.LTGRAY)
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

    private fun logoMark() {
        val mark = TextView(this).apply {
            text = "🌳\n🔥🔥🔥"
            textSize = 38f; gravity = Gravity.CENTER; setTextColor(Color.rgb(242, 207, 122)); setPadding(0, dp(18), 0, dp(18))
            background = rounded(Color.rgb(25, 17, 10), Color.rgb(179, 126, 49))
        }
        root.addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
    }

    private fun hero(head: String, body: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = rounded(Color.rgb(28, 22, 15), Color.rgb(91, 69, 34)) }
        box.addView(TextView(this).apply { text = head; textSize = 21f; setTextColor(Color.rgb(242, 207, 122)) })
        box.addView(TextView(this).apply { text = body; textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, dp(7), 0, 0) })
        root.addView(box, marginParams(0, 0, 0, 14))
    }

    private fun card(text: String, actions: LinearLayout.() -> Unit = {}) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(Color.rgb(24, 24, 28), Color.rgb(50, 50, 58)) }
        box.addView(TextView(this).apply { this.text = text; textSize = 16f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.15f) })
        box.actions()
        root.addView(box, marginParams(0, 0, 0, 10))
    }

    private fun title(s: String) { root.addView(TextView(this).apply { text = s; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.rgb(242, 207, 122)); setPadding(0, dp(12), 0, dp(22)) }) }
    private fun section(s: String) { root.addView(TextView(this).apply { text = s; textSize = 20f; setTextColor(Color.rgb(242, 207, 122)); setPadding(0, dp(18), 0, dp(10)) }) }
    private fun centered(s: String, size: Float, color: Int) { root.addView(TextView(this).apply { text = s; textSize = size; gravity = Gravity.CENTER; setTextColor(color) }) }
    private fun label(s: String, bg: Int = Color.rgb(24, 24, 28)) { root.addView(TextView(this).apply { text = s; textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(bg, Color.rgb(48, 48, 55)) }, marginParams(0, 0, 0, 12)) }
    private fun button(s: String, secondary: Boolean = false, action: () -> Unit) { root.addView(Button(this).apply { text = s; textSize = 16f; setTextColor(if (secondary) Color.WHITE else Color.rgb(20, 14, 7)); backgroundTintList = android.content.res.ColorStateList.valueOf(if (secondary) Color.rgb(45, 45, 52) else Color.rgb(229, 184, 92)); setOnClickListener { action() } }, marginParams(0, 10, 0, 0)) }
    private fun LinearLayout.smallButton(s: String, action: () -> Unit) { addView(Button(this@MainActivity).apply { text = s; setTextColor(Color.rgb(20, 14, 7)); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(229, 184, 92)); setOnClickListener { action() } }, marginParams(0, 8, 0, 0)) }
    private fun back(action: () -> Unit) { root.addView(Button(this).apply { text = "← Terug"; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(38, 38, 44)); setOnClickListener { action() } }, marginParams(0, 0, 0, 8)) }
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
                    Order(parts[0].toInt(), items, parts[1].toDouble(), parts[2])
                } catch (_: Exception) { null }
            }.toMutableList()
        }
        private fun save(c: Context, orders: List<Order>) {
            val raw = orders.joinToString("§") { o -> "${o.id}¦${o.total}¦${o.status}¦${o.items.entries.joinToString("~") { "${it.key}=${it.value}" }}" }
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
        }
        fun create(c: Context, items: Map<String, Int>, total: Double): Order {
            val orders = all(c); val id = maxOf(1045, orders.maxOfOrNull { it.id } ?: 1045) + 1
            val o = Order(id, LinkedHashMap(items), total, "Nieuw"); orders += o; save(c, orders); return o
        }
        fun upsert(c: Context, order: Order) { val orders = all(c); val i = orders.indexOfFirst { it.id == order.id }; if (i >= 0) orders[i] = order else orders += order; save(c, orders) }
        fun status(c: Context, id: Int, status: String) { val orders = all(c); orders.firstOrNull { it.id == id }?.status = status; save(c, orders) }
    }
}
