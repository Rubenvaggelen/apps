package com.gmailorg.hub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HomeActivity : AppCompatActivity() {

    // Vast adres van de Gmail Org web-app (mail + kalender). Wijzig hier als
    // je 'm ooit naar een andere URL verhuist.
    private val mailUrl = "https://rubenvaggelen.github.io/Gmailorg/"

    private lateinit var adapter: HomeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        ShortcutStore.init(applicationContext)
        cleanUpMissingShortcuts()

        val grid = findViewById<RecyclerView>(R.id.homeGrid)
        grid.layoutManager = GridLayoutManager(this, 3)
        adapter = HomeAdapter(
            onTileClick = ::handleTileClick,
            onTileLongClick = ::handleTileLongClick
        )
        grid.adapter = adapter
        refreshTiles()

        // Zorgt dat de parkeermeldingen voor al je opgeslagen adressen
        // geregistreerd staan zodra locatietoestemming beschikbaar is.
        ParkingGeofenceManager.syncAll(this)

        // Ververst de supermarkt-geofences naar je huidige locatie, zodat
        // meldingen blijven werken als je ergens anders bent dan waar je de
        // functie oorspronkelijk hebt aangezet.
        if (SupermarketGeofenceManager.isEnabled(this)) {
            SupermarketGeofenceManager.enableForCurrentLocation(this) { _, _ -> }
            SupermarketRefreshWorker.schedule(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTiles() // eventueel net toegevoegde app tonen
    }

    /**
     * Verwijdert zelf toegevoegde app-snelkoppelingen waarvan de app niet
     * meer op het toestel geïnstalleerd staat — voorkomt lege/kale
     * icoontjes voor apps die je hebt gedeïnstalleerd.
     */
    private fun cleanUpMissingShortcuts() {
        val stale = ShortcutStore.getAll().filter { tile ->
            val packageName = tile.packageName ?: return@filter false
            !isPackageInstalled(packageName)
        }
        stale.forEach { tile -> tile.packageName?.let { ShortcutStore.remove(it) } }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun refreshTiles() {
        val fixed = listOf(
            HomeTile(id = "notifications", type = TileType.NOTIFICATIONS, label = "Meldingen"),
            HomeTile(id = "mail", type = TileType.MAIL, label = "Mail & Kalender"),
            HomeTile(id = "household", type = TileType.HOUSEHOLD, label = "Huishouden"),
            HomeTile(id = "movies", type = TileType.MOVIES, label = "Films & Series"),
            HomeTile(id = "parking", type = TileType.PARKING, label = "Parkeren"),
            HomeTile(id = "settings", type = TileType.SETTINGS, label = "Instellingen"),
            HomeTile(id = "ask", type = TileType.ASK, label = "Vraag het"),
            HomeTile(id = "recipes", type = TileType.RECIPES, label = "Recepten"),
            HomeTile(id = "news", type = TileType.NEWS, label = "Nieuws"),
            HomeTile(id = "whatsapp", type = TileType.APP, label = "WhatsApp", packageName = "com.whatsapp"),
            HomeTile(id = "googlehome", type = TileType.APP, label = "Google Home", packageName = "com.google.android.apps.chromecast.app")
        ).filter { tile ->
            // Vaste snelkoppelingen naar apps (WhatsApp, Google Home) alleen
            // tonen als die app ook daadwerkelijk geïnstalleerd staat —
            // anders zie je een leeg "+"-icoontje voor een niet-bestaande app.
            tile.packageName == null || isPackageInstalled(tile.packageName)
        }
        val userApps = ShortcutStore.getAll()
        val addButton = HomeTile(id = "add", type = TileType.ADD_BUTTON, label = "App toevoegen")
        adapter.updateTiles(fixed + userApps + addButton)
    }

    private fun handleTileClick(tile: HomeTile) {
        when (tile.type) {
            TileType.NOTIFICATIONS -> startActivity(Intent(this, NotificationsActivity::class.java))
            TileType.MAIL -> openMailInCustomTab()
            TileType.HOUSEHOLD -> startActivity(Intent(this, HouseholdActivity::class.java))
            TileType.MOVIES -> startActivity(Intent(this, MoviesActivity::class.java))
            TileType.PARKING -> startActivity(Intent(this, ParkingActivity::class.java))
            TileType.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            TileType.ASK -> startActivity(Intent(this, AskActivity::class.java))
            TileType.RECIPES -> startActivity(Intent(this, RecipesActivity::class.java))
            TileType.NEWS -> startActivity(Intent(this, NewsActivity::class.java))
            TileType.APP -> launchExternalApp(tile.packageName)
            TileType.ADD_BUTTON -> startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    private fun handleTileLongClick(tile: HomeTile): Boolean {
        if (tile.type != TileType.APP || tile.packageName == null) return false
        // Alleen zelf toegevoegde snelkoppelingen zijn te verwijderen — de
        // vaste WhatsApp-tegel blijft altijd staan.
        val isUserAdded = ShortcutStore.getAll().any { it.packageName == tile.packageName }
        if (!isUserAdded) return false
        ShortcutStore.remove(tile.packageName)
        refreshTiles()
        Toast.makeText(this, "${tile.label} verwijderd", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun openMailInCustomTab() {
        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(this, Uri.parse(mailUrl))
    }

    private fun launchExternalApp(packageName: String?) {
        if (packageName == null) return
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Kan deze app niet openen (mogelijk verwijderd)", Toast.LENGTH_SHORT).show()
        }
    }
}

class HomeAdapter(
    private val onTileClick: (HomeTile) -> Unit,
    private val onTileLongClick: (HomeTile) -> Boolean
) : RecyclerView.Adapter<HomeAdapter.ViewHolder>() {

    private var tiles: List<HomeTile> = emptyList()

    fun updateTiles(newTiles: List<HomeTile>) {
        tiles = newTiles
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.tileIcon)
        val label: TextView = view.findViewById(R.id.tileLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_tile, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = tiles.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tile = tiles[position]
        holder.label.text = tile.label
        val context = holder.itemView.context

        val iconDrawable = when (tile.type) {
            TileType.NOTIFICATIONS -> ContextCompat.getDrawable(context, R.drawable.ic_tile_notifications)
            TileType.MAIL -> ContextCompat.getDrawable(context, R.drawable.ic_tile_mail)
            TileType.HOUSEHOLD -> ContextCompat.getDrawable(context, R.drawable.ic_tile_household)
            TileType.MOVIES -> ContextCompat.getDrawable(context, R.drawable.ic_tile_movies)
            TileType.PARKING -> ContextCompat.getDrawable(context, R.drawable.ic_tile_parking)
            TileType.SETTINGS -> ContextCompat.getDrawable(context, R.drawable.ic_tile_settings)
            TileType.ASK -> ContextCompat.getDrawable(context, R.drawable.ic_tile_ask)
            TileType.RECIPES -> ContextCompat.getDrawable(context, R.drawable.ic_tile_recipes)
            TileType.NEWS -> ContextCompat.getDrawable(context, R.drawable.ic_tile_news)
            TileType.ADD_BUTTON -> ContextCompat.getDrawable(context, R.drawable.ic_tile_add)
            TileType.APP -> try {
                context.packageManager.getApplicationIcon(tile.packageName!!)
            } catch (e: Exception) {
                ContextCompat.getDrawable(context, R.drawable.ic_tile_add)
            }
        }
        holder.icon.setImageDrawable(iconDrawable)

        holder.itemView.setOnClickListener { onTileClick(tile) }
        holder.itemView.setOnLongClickListener { onTileLongClick(tile) }
    }
}
