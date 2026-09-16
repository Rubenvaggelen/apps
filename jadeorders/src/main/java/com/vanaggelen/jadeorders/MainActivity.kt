package com.vanaggelen.jadeorders

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val products = listOf(
        Product("Teriyaki Chicken", 12.50), Product("The Emperor Burger", 14.95),
        Product("Ube Cheesecake", 6.95), Product("Nasi Special", 11.50),
        Product("Roti Kip", 13.50), Product("Friet groot", 4.25),
        Product("Cola", 2.75), Product("Iced Tea", 2.75)
    )
    private val cart = linkedMapOf<String, Int>()
    private lateinit var root: LinearLayout
    private val money = NumberFormat.getCurrencyInstance(Locale("nl", "NL"))

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); landing() }

    private fun page() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 40)
            setBackgroundColor(Color.rgb(11,11,13))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun landing() {
        page(); title("The Jade Emperor"); text("GOOD FOOD • GREATER MOMENTS")
        button("Bestellen als klant") { customer() }
        button("Bedrijf • Bestellingen ontvangen") { business() }
    }

    private fun customer() {
        page(); back { landing() }; title("Bestellen")
        products.forEach { p ->
            label("${p.name}  •  ${money.format(p.price)}")
            button("+ Toevoegen") { cart[p.name] = (cart[p.name] ?: 0) + 1; customer() }
        }
        button("Winkelmand (${cart.values.sum()})") { cart() }
        button("Mijn bestellingen") { myOrders() }
    }

    private fun cart() {
        page(); back { customer() }; title("Winkelmand")
        if (cart.isEmpty()) { text("Je winkelmand is leeg."); return }
        var total = 0.0
        cart.forEach { (name, qty) ->
            val p = products.first { it.name == name }
            total += p.price * qty
            label("$qty× $name — ${money.format(p.price * qty)}")
        }
        label("Totaal: ${money.format(total)}")
        button("Bestelling plaatsen") {
            val order = Store.create(this, cart, total)
            cart.clear(); Toast.makeText(this, "Bestelling #${order.id} geplaatst", Toast.LENGTH_LONG).show(); myOrders()
        }
    }

    private fun myOrders() {
        page(); back { customer() }; title("Mijn bestellingen")
        Store.all(this).reversed().forEach { o -> orderView(o, false) }
    }

    private fun business() {
        page(); back { landing() }; title("Bedrijf Dashboard")
        val orders = Store.all(this).reversed()
        label("${orders.count { it.status == "Nieuw" }} nieuwe bestellingen")
        orders.forEach { o -> orderView(o, true) }
        button("Verversen") { business() }
    }

    private fun orderView(o: Order, admin: Boolean) {
        label("#${o.id} • ${o.status} • ${money.format(o.total)}")
        text(o.items.entries.joinToString("\n") { "${it.value}× ${it.key}" })
        if (admin) when (o.status) {
            "Nieuw" -> { button("Accepteren") { Store.status(this, o.id, "In bereiding"); business() }; button("Weigeren") { Store.status(this, o.id, "Geweigerd"); business() } }
            "In bereiding" -> button("Klaar") { Store.status(this, o.id, "Klaar"); business() }
            "Klaar" -> button("Afronden") { Store.status(this, o.id, "Afgerond"); business() }
        }
    }

    private fun title(s:String) = root.addView(TextView(this).apply { text=s; textSize=28f; setTextColor(Color.rgb(242,207,122)); setPadding(0,12,0,22) })
    private fun label(s:String) = root.addView(TextView(this).apply { text=s; textSize=18f; setTextColor(Color.WHITE); setPadding(0,16,0,8) })
    private fun text(s:String) = root.addView(TextView(this).apply { text=s; textSize=14f; setTextColor(Color.LTGRAY); setPadding(0,0,0,14) })
    private fun button(s:String, action:()->Unit) = root.addView(Button(this).apply { text=s; isAllCaps=false; setOnClickListener { action() } })
    private fun back(action:()->Unit) = button("← Terug", action)
}

data class Product(val name:String, val price:Double)
data class Order(val id:Int, val items:Map<String,Int>, val total:Double, var status:String)

object Store {
    private const val PREF="jade_orders"; private const val KEY="orders"
    fun all(c:Context):MutableList<Order> {
        val a=JSONArray(c.getSharedPreferences(PREF,0).getString(KEY,"[]") ?: "[]")
        return MutableList(a.length()) { i ->
            val o=a.getJSONObject(i); val j=o.getJSONObject("items"); val m=linkedMapOf<String,Int>()
            j.keys().forEach { k -> m[k]=j.getInt(k) }
            Order(o.getInt("id"),m,o.getDouble("total"),o.getString("status"))
        }
    }
    fun create(c:Context, cart:Map<String,Int>, total:Double):Order {
        val all=all(c); val o=Order((all.maxOfOrNull{it.id}?:1045)+1,LinkedHashMap(cart),total,"Nieuw"); all.add(o); save(c,all); return o
    }
    fun status(c:Context,id:Int,s:String){ val all=all(c); all.firstOrNull{it.id==id}?.status=s; save(c,all) }
    private fun save(c:Context,all:List<Order>){
        val a=JSONArray(); all.forEach { o -> val items=JSONObject(); o.items.forEach{(k,v)->items.put(k,v)}; a.put(JSONObject().put("id",o.id).put("items",items).put("total",o.total).put("status",o.status)) }
        c.getSharedPreferences(PREF,0).edit().putString(KEY,a.toString()).apply()
    }
}
