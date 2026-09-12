package com.gmailorg.hub

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        HiddenTilesStore.init(applicationContext)
        cleanUpMissingShortcuts()

        val grid = findViewById<RecyclerView>(R.id.homeGrid)
        grid.layoutManager = GridLayoutManager(this, 3)
        adapter = HomeAdapter(
            onTileClick = ::handleTileClick,
            onTileLongClick = ::handleTileLongClick
        )
        grid.adapter = adapter
        refreshTiles()

        findViewById<TextView>(R.id.versionLabel).text = "build ${BuildConfig.VERSION_CODE}"

        UpdateChecker.checkForUpdate(this)

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
            HomeTile(id = "route", type = TileType.ROUTE, label = "Route"),
            HomeTile(id = "household", type = TileType.HOUSEHOLD, label = "Huishouden"),
            HomeTile(id = "movies", type = TileType.MOVIES, label = "Films, Series & Muziek"),
            HomeTile(id = "parking", type = TileType.PARKING, label = "Parkeren"),
            HomeTile(id = "settings", type = TileType.SETTINGS, label = "Instellingen"),
            HomeTile(id = "ask", type = TileType.ASK, label = "Vraag het"),
            HomeTile(id = "recipes", type = TileType.RECIPES, label = "Recepten"),
            HomeTile(id = "news", type = TileType.NEWS, label = "Nieuws"),
            HomeTile(id = "radio", type = TileType.RADIO, label = "Radio"),
            HomeTile(id = "currency", type = TileType.CURRENCY, label = "EUR/SRD-koers"),
            HomeTile(id = "whatsapp", type = TileType.APP, label = "WhatsApp", packageName = "com.whatsapp"),
            HomeTile(id = "googlehome", type = TileType.APP, label = "Google Home", packageName = "com.google.android.apps.chromecast.app")
        ).filter { tile ->
            // Vaste snelkoppelingen naar apps (WhatsApp, Google Home) alleen
            // tonen als die app ook daadwerkelijk geïnstalleerd staat —
            // anders zie je een leeg "+"-icoontje voor een niet-bestaande app.
            (tile.packageName == null || isPackageInstalled(tile.packageName)) &&
                !HiddenTilesStore.isHidden(tile.id)
        }
        val userApps = ShortcutStore.getAll().filter { !HiddenTilesStore.isHidden(it.id) }
        val addButton = HomeTile(id = "add", type = TileType.ADD_BUTTON, label = "App toevoegen")
        adapter.updateTiles(fixed + userApps + addButton)
    }

    private fun handleTileClick(tile: HomeTile) {
        when (tile.type) {
            TileType.NOTIFICATIONS -> startActivity(Intent(this, NotificationsActivity::class.java))
            TileType.MAIL -> openMailInCustomTab()
            TileType.ROUTE -> openRouteInCustomTab()
            TileType.HOUSEHOLD -> startActivity(Intent(this, HouseholdActivity::class.java))
            TileType.MOVIES -> startActivity(Intent(this, MoviesActivity::class.java))
            TileType.PARKING -> startActivity(Intent(this, ParkingActivity::class.java))
            TileType.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            TileType.ASK -> startActivity(Intent(this, AskActivity::class.java))
            TileType.RECIPES -> startActivity(Intent(this, RecipesActivity::class.java))
            TileType.NEWS -> startActivity(Intent(this, NewsActivity::class.java))
            TileType.RADIO -> startActivity(Intent(this, RadioActivity::class.java))
            TileType.CURRENCY -> startActivity(Intent(this, CurrencyActivity::class.java))
            TileType.APP -> launchExternalApp(tile.packageName)
            TileType.ADD_BUTTON -> startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    private fun handleTileLongClick(tile: HomeTile): Boolean {
        if (tile.type == TileType.ADD_BUTTON) return false

        AlertDialog.Builder(this)
            .setTitle("Tegel verbergen?")
            .setMessage("\"${tile.label}\" wordt van het startscherm verwijderd. Je kunt 'm later terugzetten via Instellingen.")
            .setPositiveButton("Verbergen") { _, _ ->
                if (tile.type == TileType.APP && tile.packageName != null &&
                    ShortcutStore.getAll().any { it.packageName == tile.packageName }
                ) {
                    // Zelf toegevoegde app-snelkoppeling: gewoon volledig verwijderen,
                    // opnieuw toevoegen kan altijd via "App toevoegen".
                    ShortcutStore.remove(tile.packageName)
                } else {
                    // Vaste tegel: verbergen maar onthouden, terug te zetten via Instellingen.
                    HiddenTilesStore.hide(tile.id)
                }
                refreshTiles()
                Toast.makeText(this, "${tile.label} verborgen", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuleren", null)
            .show()
        return true
    }

    private fun openMailInCustomTab() {
        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(this, Uri.parse(mailUrl))
    }

    private fun openRouteInCustomTab() {
        // #route-standalone zorgt dat Gmail Org direct het Route-tabblad
        // opent, mét de rest van de Mail/Kalender-navigatie verborgen —
        // voelt zo als een volledig losse Route-app.
        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(this, Uri.parse("$mailUrl#route-standalone"))
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
            TileType.NOTIFICATIONS -> ContextCompat.getDrawable(context, R.drawable.ic_home_notifications_fancy)
            TileType.MAIL -> ContextCompat.getDrawable(context, R.drawable.ic_home_mail_fancy)
            TileType.ROUTE -> ContextCompat.getDrawable(context, R.drawable.ic_home_route_fancy)
            TileType.HOUSEHOLD -> ContextCompat.getDrawable(context, R.drawable.ic_home_household_fancy)
            TileType.MOVIES -> ContextCompat.getDrawable(context, R.drawable.ic_home_movies_fancy)
            TileType.PARKING -> ContextCompat.getDrawable(context, R.drawable.ic_home_parking_fancy)
            TileType.SETTINGS -> ContextCompat.getDrawable(context, R.drawable.ic_home_settings_fancy)
            TileType.ASK -> ContextCompat.getDrawable(context, R.drawable.ic_home_ask_fancy)
            TileType.RECIPES -> ContextCompat.getDrawable(context, R.drawable.ic_home_recipes_fancy)
            TileType.NEWS -> ContextCompat.getDrawable(context, R.drawable.ic_home_news_fancy)
            TileType.RADIO -> ContextCompat.getDrawable(context, R.drawable.ic_home_radio_fancy)
            TileType.CURRENCY -> ContextCompat.getDrawable(context, R.drawable.ic_home_currency_fancy)
            TileType.ADD_BUTTON -> ContextCompat.getDrawable(context, R.drawable.ic_home_add_fancy)
            TileType.APP -> try {
                buildBadgedAppIcon(context, context.packageManager.getApplicationIcon(tile.packageName!!))
            } catch (e: Exception) {
                ContextCompat.getDrawable(context, R.drawable.ic_home_add_fancy)
            }
        }
        holder.icon.setImageDrawable(iconDrawable)

        holder.itemView.setOnClickListener { onTileClick(tile) }
        holder.itemView.setOnLongClickListener { onTileLongClick(tile) }
    }

    /**
     * Geeft een zelf toegevoegde app (WhatsApp, Google Home, of een app die
     * de gebruiker zelf toevoegt via "App toevoegen") dezelfde luxe gouden
     * ring/donkergroene-cirkel-badge als de vaste tegels, met het eigen
     * app-icoon (verkleind en gecentreerd) erin — in plaats van het kale
     * launcher-icoon dat qua stijl niet bij de rest paste.
     */
    private fun buildBadgedAppIcon(context: Context, appIcon: Drawable): Drawable {
        val badge = ContextCompat.getDrawable(context, R.drawable.bg_home_tile_badge)!!.mutate()
        val layered = LayerDrawable(arrayOf(badge, appIcon))
        // Zelfde verhouding als de glyphs in de vaste badges (~31dp icoon
        // gecentreerd in een 56dp tegel, dus ~12-13dp inspringen rondom).
        val inset = (13 * context.resources.displayMetrics.density).toInt()
        layered.setLayerInset(1, inset, inset, inset, inset)
        return layered
    }
}
