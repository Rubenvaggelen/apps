package com.gmailorg.hub

import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.browser.customtabs.CustomTabsIntent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Ordinary The One app. Its installation is separate from the owner Main APK.
 * No owner console, PINs, or owner-account bootstrap are exposed here.
 */
class UserHomeActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val blue = Color.rgb(42, 158, 255)
    private val white = Color.rgb(238, 247, 255)
    private val dim = Color.rgb(155, 183, 211)
    private val bg = Color.rgb(6, 15, 34)
    private val cardColor = Color.rgb(15, 34, 62)
    private var busy = false
    private var menuVisible = false
    private lateinit var menuAdapter: HomeAdapter
    private var active = false
    private val poll = object : Runnable {
        override fun run() {
            if (!active || isFinishing || isDestroyed) return
            if (MainDeviceRegistry.hasPersonName(this@UserHomeActivity)) checkRegistration()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.IS_USER_APP) { finish(); return }
        UpdateChecker.checkForUpdate(this)
        if (MainDeviceRegistry.hasPersonName(this)) showChecking()
        else showNameScreen()
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (menuVisible) refreshMenuTiles()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        active = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    /** Same logo, watermark and header as Main, including the approved artwork. */
    private fun shell(): RecyclerView {
        setContentView(R.layout.activity_home)
        findViewById<TextView>(R.id.appTitle).text = "THE ONE"
        findViewById<TextView>(R.id.appRole).text = "GEBRUIKER"
        findViewById<TextView>(R.id.versionLabel).text = "build " + BuildConfig.VERSION_CODE
        return findViewById(R.id.homeGrid)
    }

    private fun root(): LinearLayout {
        menuVisible = false
        shell().visibility = View.GONE
        val original = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundResource(R.drawable.bg_the_one_tile)
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
        }
        original.addView(content, params)
        return content
    }

    private fun label(parent: LinearLayout, message: String, size: Float = 16f) {
        parent.addView(TextView(this).apply {
            text = message
            textSize = size
            setTextColor(white)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(14))
        })
    }

    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 16f
            setTextColor(white)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(cardColor))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { action() }
        }
        parent.addView(b, LinearLayout.LayoutParams(-1, dp(60)).apply {
            bottomMargin = dp(9)
        })
    }

    private fun showNameScreen() {
        val parent = root()
        label(parent, "Welkom bij The One\nVul je naam in om toegang aan te vragen.", 19f)
        val name = EditText(this).apply {
            setSingleLine(true)
            hint = "Voornaam en achternaam"
            setTextColor(white)
            setHintTextColor(dim)
            setText(MainDeviceRegistry.personName(this@UserHomeActivity))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        parent.addView(name)
        button(parent, "Toegang aanvragen") {
            val submitted = name.text.toString().trim().replace(Regex("\\s+"), " ")
            if (submitted.length !in 2..80 ||
                submitted.any { java.lang.Character.isISOControl(it) }) {
                name.error = "Vul een geldige naam in."
                return@button
            }
            MainDeviceRegistry.savePersonRegistration(this, submitted, false)
            showChecking()
            checkRegistration()
        }
        label(parent, "De eigenaar ontvangt de aanvraag in Main. Je krijgt geen toegang zonder goedkeuring.", 13f)
    }

    private fun showChecking() {
        val parent = root()
        label(parent, "Registratie controleren…")
    }

    private fun showWaiting(message: String) {
        val parent = root()
        label(parent, message, 18f)
        label(parent, "Je registratie wordt automatisch opnieuw gecontroleerd.\nJe hoeft de app niet opnieuw te installeren.", 14f)
        button(parent, "Nu opnieuw controleren") { checkRegistration() }
        button(parent, "Naam aanpassen") { showNameScreen() }
    }

    private fun checkRegistration() {
        if (!active || busy || !MainDeviceRegistry.hasPersonName(this)) return
        busy = true
        Thread {
            val result = runCatching {
                val status = UserAppLicense.registerAndCheck(MainDeviceRegistry.personName(this))
                if (status.allowed) MainDeviceRegistry.heartbeat(this)
                status
            }
            runOnUiThread {
                busy = false
                if (!active || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { status ->
                    when {
                        status.allowed -> showMenu()
                        status.pending -> showWaiting("Aanvraag verzonden.\nWachten op goedkeuring van de eigenaar.")
                        else -> showWaiting("Toegang is nog niet verleend.\nDe eigenaar kan dit in Apparaten beheren wijzigen.")
                    }
                }.onFailure {
                    val reason = it.message.orEmpty().take(180)
                    showWaiting("Registratie niet bevestigd.\nControleer je internetverbinding.\n$reason")
                }
            }
        }.start()
    }

    private fun openFeature(scope: String, title: String, target: Class<out android.app.Activity>) {
        if (busy) return
        busy = true
        Thread {
            val response = runCatching { MainDeviceRegistry.refreshAccess(this, scope) }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                response.onSuccess { status ->
                    if (status.allowed) {
                        startActivity(Intent(this, target))
                    } else if (status.pending) {
                        android.widget.Toast.makeText(this,
                            "De aanvraag voor $title wacht op goedkeuring.",
                            android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("$title aanvragen")
                            .setMessage("Wil je de eigenaar toestemming vragen voor $title?")
                            .setNegativeButton("Annuleren", null)
                            .setPositiveButton("Aanvragen") { _, _ -> requestScope(scope, title) }
                            .show()
                    }
                }.onFailure { showWaiting("Verbinding verbroken. Controleer de registratie opnieuw.") }
            }
        }.start()
    }

    private fun requestScope(scope: String, title: String) {
        Thread {
            val pending = runCatching { MainDeviceRegistry.requestAccess(this, scope) }.getOrNull()
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    if (pending?.pending == true) "Aanvraag voor $title is verstuurd."
                    else "Aanvraag niet bevestigd. Probeer het opnieuw.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /**
     * Reuses Main's actual three-column HomeAdapter, official logo artwork,
     * tile backgrounds and exact tile labels. Only admin entry points are removed.
     */
    private fun showMenu() {
        menuVisible = true
        val grid = shell()
        grid.layoutManager = GridLayoutManager(this, 3)
        menuAdapter = HomeAdapter(::handleUserTile, { false })
        grid.adapter = menuAdapter
        refreshMenuTiles()
    }

    private val familyScopes = mapOf(
        "com.theone.cinema" to MainDeviceRegistry.ACCESS_CINEMA,
        "com.theone.series" to MainDeviceRegistry.ACCESS_SERIES,
        "com.theone.tv.phone" to MainDeviceRegistry.ACCESS_TV,
        "com.gmailorg.thedj" to MainDeviceRegistry.ACCESS_DJ,
        "com.gmailorg.runcoach" to MainDeviceRegistry.ACCESS_RUN
    )

    private fun fixedUserTiles(): List<HomeTile> = listOf(
        HomeTile("notifications", TileType.NOTIFICATIONS, "Meldingen"),
        HomeTile("mail", TileType.MAIL, "Mail & Kalender"),
        HomeTile("route", TileType.ROUTE, "Route"),
        HomeTile("household", TileType.HOUSEHOLD, "Huishouden"),
        HomeTile("movies", TileType.MOVIES, "Muziek en films"),
        HomeTile("parking", TileType.PARKING, "Parkeren"),
        HomeTile("ask", TileType.ASK, "Vraag het"),
        HomeTile("recipes", TileType.RECIPES, "Recepten"),
        HomeTile("news", TileType.NEWS, "Nieuws"),
        HomeTile("radio", TileType.RADIO, "Radio"),
        HomeTile("currency", TileType.CURRENCY, "Koers (EUR / SRD / USD)"),
        HomeTile("com.theone.cinema", TileType.APP, "Cinema", "com.theone.cinema"),
        HomeTile("com.theone.series", TileType.APP, "Series", "com.theone.series"),
        HomeTile("com.theone.tv.phone", TileType.APP, "TV", "com.theone.tv.phone"),
        HomeTile("com.gmailorg.thedj", TileType.APP, "The One DJ", "com.gmailorg.thedj"),
        HomeTile("com.gmailorg.runcoach", TileType.APP, "The One Run", "com.gmailorg.runcoach"),
        HomeTile("whatsapp", TileType.APP, "WhatsApp", "com.whatsapp"),
        HomeTile("googlehome", TileType.APP, "Google Home", "com.google.android.apps.chromecast.app")
    )

    private fun installed(packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0); true }.getOrDefault(false)

    private fun refreshMenuTiles() {
        if (!menuVisible || !::menuAdapter.isInitialized) return
        val standard = fixedUserTiles().filter { tile ->
            (tile.id in familyScopes || tile.packageName == null || installed(tile.packageName)) &&
                !HiddenTilesStore.isHidden(tile.id)
        }
        val custom = ShortcutStore.getAll().filter { shortcut ->
            shortcut.id !in standard.map { it.id } && !HiddenTilesStore.isHidden(shortcut.id)
        }
        menuAdapter.updateTiles(standard + custom +
            HomeTile("add", TileType.ADD_BUTTON, "App toevoegen"))
    }

    private fun activity(target: Class<out android.app.Activity>) {
        startActivity(Intent(this, target))
    }

    private fun external(packageName: String, label: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) startActivity(launch)
        else AlertDialog.Builder(this)
            .setTitle(label + " installeren")
            .setMessage("Deze app is nog niet op dit toestel geïnstalleerd.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openLicensedExternal(packageName: String, label: String, scope: String) {
        if (busy) return
        busy = true
        Thread {
            val result = runCatching {
                if (!UserAppLicense.checkAccess().allowed) throw SecurityException("Toegang ingetrokken")
                MainDeviceRegistry.refreshAccess(this, scope)
            }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { status ->
                    when {
                        status.allowed -> external(packageName, label)
                        status.pending -> android.widget.Toast.makeText(this,
                            "De aanvraag voor " + label + " is nog in behandeling.",
                            android.widget.Toast.LENGTH_LONG).show()
                        else -> AlertDialog.Builder(this)
                            .setTitle("Toestemming aanvragen voor " + label)
                            .setMessage("De eigenaar moet deze app eerst toestaan.")
                            .setNegativeButton("Annuleren", null)
                            .setPositiveButton("Aanvragen") { _, _ ->
                                Thread {
                                    val request = runCatching {
                                        MainDeviceRegistry.requestAccess(this, scope)
                                    }.getOrNull()
                                    runOnUiThread {
                                        android.widget.Toast.makeText(this,
                                            if (request?.pending == true) "Aanvraag verzonden."
                                            else "Aanvraag niet bevestigd.",
                                            android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }.start()
                            }.show()
                    }
                }.onFailure {
                    showWaiting("Verbinding verbroken of toegang ingetrokken.\nControleer opnieuw.")
                }
            }
        }.start()
    }

    private fun handleUserTile(tile: HomeTile) {
        familyScopes[tile.id]?.let { scope ->
            openLicensedExternal(tile.packageName.orEmpty(), tile.label, scope)
            return
        }
        when (tile.type) {
            TileType.NOTIFICATIONS -> activity(NotificationsActivity::class.java)
            TileType.MAIL -> CustomTabsIntent.Builder().build().launchUrl(
                this, Uri.parse("https://rubenvaggelen.github.io/Gmailorg/"))
            TileType.ROUTE -> activity(RouteHubActivity::class.java)
            TileType.HOUSEHOLD -> activity(HouseholdActivity::class.java)
            TileType.MOVIES -> activity(MoviesActivity::class.java)
            TileType.PARKING -> activity(ParkingActivity::class.java)
            TileType.ASK -> activity(AskActivity::class.java)
            TileType.RECIPES -> activity(RecipesActivity::class.java)
            TileType.NEWS -> activity(NewsActivity::class.java)
            TileType.RADIO -> activity(RadioActivity::class.java)
            TileType.CURRENCY -> activity(CurrencyActivity::class.java)
            TileType.ADD_BUTTON -> activity(AppPickerActivity::class.java)
            TileType.APP -> tile.packageName?.let { external(it, tile.label) }
            else -> android.widget.Toast.makeText(this,
                "Alleen beschikbaar voor de beheerder.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

}
