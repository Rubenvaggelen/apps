package com.gmailorg.hub

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
    private val pendingNotificationListener: () -> Unit = {
        runOnUiThread { if (!isFinishing && !isDestroyed) refreshTiles() }
    }

    private var blockedDialogShowing = false
    private var mainLicenseDialogShowing = false
    private var personRegistrationDialogShowing = false
    private var personRegistrationLookupRunning = false
    private val accessRequestPoll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                if (MainDeviceRegistry.isLocallyOwner(this@HomeActivity)) {
                    AccessRequestNotificationWorker.checkNow(this@HomeActivity)
                }
                // Always reschedule: on first launch the owner flag can be
                // populated after the first poll by the remote heartbeat.
                findViewById<RecyclerView>(R.id.homeGrid).postDelayed(this, 30_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        NotifStore.init(applicationContext)
        NotifStore.subscribe(pendingNotificationListener)
        ShortcutStore.init(applicationContext)
        HiddenTilesStore.init(applicationContext)
        cleanUpMissingShortcuts()

        val grid = findViewById<RecyclerView>(R.id.homeGrid)
        // No Main tiles become interactive until this installation is licensed.
        grid.visibility = View.INVISIBLE
        grid.layoutManager = GridLayoutManager(this, 3)
        adapter = HomeAdapter(
            onTileClick = ::handleTileClick,
            onTileLongClick = ::handleTileLongClick
        )
        grid.adapter = adapter
        refreshTiles()

        findViewById<TextView>(R.id.versionLabel).text = "build ${BuildConfig.VERSION_CODE}"

        UpdateChecker.checkForUpdate(this)
        ensurePersonRegistration()

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
        SupremacyPlaybackService.resumeLastSessionIfNeeded(this)
        refreshTiles() // eventueel net toegevoegde app tonen
        ensurePersonRegistration()
        val grid = findViewById<RecyclerView>(R.id.homeGrid)
        grid.removeCallbacks(accessRequestPoll)
        grid.post(accessRequestPoll)
    }

    override fun onDestroy() {
        NotifStore.unsubscribe(pendingNotificationListener)
        super.onDestroy()
    }

    override fun onPause() {
        findViewById<RecyclerView>(R.id.homeGrid).removeCallbacks(accessRequestPoll)
        super.onPause()
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
        val waitingCount = if (MainDeviceRegistry.isLocallyOwner(this)) {
            NotifStore.getAll().count {
                it.actionType == "license_request" || it.actionType == "access_request"
            }
        } else 0
        val fixed = listOf(
            HomeTile(id = "notifications", type = TileType.NOTIFICATIONS,
                label = if (waitingCount > 0) "Meldingen • $waitingCount" else "Meldingen"),
            HomeTile(id = "mail", type = TileType.MAIL, label = "Mail & Kalender"),
            HomeTile(id = "route", type = TileType.ROUTE, label = "Route"),
            HomeTile(id = "household", type = TileType.HOUSEHOLD, label = "Huishouden"),
            HomeTile(id = "movies", type = TileType.MOVIES, label = "Muziek en films"),
            HomeTile(id = "parking", type = TileType.PARKING, label = "Parkeren"),
            HomeTile(id = "settings", type = TileType.SETTINGS, label = "Instellingen"),
            HomeTile(id = "ask", type = TileType.ASK, label = "Vraag het"),
            HomeTile(id = "recipes", type = TileType.RECIPES, label = "Recepten"),
            HomeTile(id = "news", type = TileType.NEWS, label = "Nieuws"),
            HomeTile(id = "radio", type = TileType.RADIO, label = "Radio"),
            HomeTile(id = "currency", type = TileType.CURRENCY, label = "Koers (EUR / SRD / USD)"),
            HomeTile(id = "com.gmailorg.thedj", type = TileType.APP, label = "The One DJ", packageName = "com.gmailorg.thedj"),
            HomeTile(id = "com.gmailorg.runcoach", type = TileType.APP, label = "The One Run", packageName = "com.gmailorg.runcoach"),
            HomeTile(id = "remote_pc", type = TileType.REMOTE_PC, label = "Laptop"),
            HomeTile(id = "whatsapp", type = TileType.APP, label = "WhatsApp", packageName = "com.whatsapp"),
            HomeTile(id = "googlehome", type = TileType.APP, label = "Google Home", packageName = "com.google.android.apps.chromecast.app")
        ).filter { tile ->
            // Vaste snelkoppelingen naar apps (WhatsApp, Google Home) alleen
            // tonen als die app ook daadwerkelijk geïnstalleerd staat —
            // anders zie je een leeg "+"-icoontje voor een niet-bestaande app.
            (tile.id in setOf("com.gmailorg.thedj", "com.gmailorg.runcoach") || tile.packageName == null || isPackageInstalled(tile.packageName)) &&
                (tile.id != "remote_pc" || MainDeviceRegistry.isLocallyOwner(this)) &&
                (tile.id in setOf("com.gmailorg.thedj", "com.gmailorg.runcoach") || !HiddenTilesStore.isHidden(tile.id))
        }
        val userApps = ShortcutStore.getAll().filter {
            !HiddenTilesStore.isHidden(it.id) && it.packageName !in setOf("com.gmailorg.thedj", "com.gmailorg.runcoach")
        }
        val addButton = HomeTile(id = "add", type = TileType.ADD_BUTTON, label = "App toevoegen")
        adapter.updateTiles(fixed + userApps + addButton)
    }

    private fun handleTileClick(tile: HomeTile) {
        if (tile.id == "com.gmailorg.thedj") { openDj(); return }
        if (tile.id == "com.gmailorg.runcoach") { openRun(); return }
        when (tile.type) {
            TileType.NOTIFICATIONS -> startActivity(Intent(this, NotificationsActivity::class.java))
            TileType.MAIL -> openMailInCustomTab()
            TileType.ROUTE -> startActivity(Intent(this, RouteHubActivity::class.java))
            TileType.HOUSEHOLD -> startActivity(Intent(this, HouseholdActivity::class.java))
            TileType.MOVIES -> startActivity(Intent(this, MoviesActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
            TileType.PARKING -> startActivity(Intent(this, ParkingActivity::class.java))
            TileType.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            TileType.ASK -> startActivity(Intent(this, AskActivity::class.java))
            TileType.RECIPES -> startActivity(Intent(this, RecipesActivity::class.java))
            TileType.NEWS -> startActivity(Intent(this, NewsActivity::class.java))
            TileType.RADIO -> startActivity(Intent(this, RadioActivity::class.java))
            TileType.CURRENCY -> startActivity(Intent(this, CurrencyActivity::class.java))
            TileType.FINANCE -> startActivity(Intent(this, FinanceActivity::class.java))
            TileType.LIFESTYLE -> startActivity(Intent(this, LifestyleActivity::class.java))
            TileType.FITNESS -> startActivity(Intent(this, FitnessActivity::class.java))
            TileType.REMOTE_PC -> startActivity(Intent(this, WakePcActivity::class.java))
            TileType.APP -> launchExternalApp(tile.packageName)
            TileType.ADD_BUTTON -> startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    private fun handleTileLongClick(tile: HomeTile): Boolean {
        if (tile.type == TileType.ADD_BUTTON) return false
        if (tile.id in setOf("com.gmailorg.thedj", "com.gmailorg.runcoach")) return true

        AlertDialog.Builder(this)
            .setTitle("Tegel verbergen?")
            .setMessage("\"${tile.label}\" wordt van het startscherm verwijderd. Je kunt 'm later terugzetten via Instellingen.")
            .setPositiveButton("Verbergen") { _, _ ->
                if (tile.type == TileType.APP && tile.packageName != null && tile.packageName !in setOf("com.gmailorg.thedj", "com.gmailorg.runcoach") &&
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

    private fun ensurePersonRegistration() {
        if (MainDeviceRegistry.hasPersonName(this)) {
            checkDeviceAccess()
            return
        }
        if (
            personRegistrationDialogShowing ||
            personRegistrationLookupRunning ||
            isFinishing ||
            isDestroyed
        ) return

        personRegistrationLookupRunning = true
        Thread {
            val alreadyRegistered = runCatching {
                MainDeviceRegistry.isTheOneRegisteredRemotely()
            }.getOrDefault(true)

            val allowTheOneSelection =
                MainDeviceRegistry.isOwnerEligible() && !alreadyRegistered

            runOnUiThread {
                personRegistrationLookupRunning = false
                showPersonRegistrationDialog(allowTheOneSelection)
            }
        }.start()
    }

    private fun showPersonRegistrationDialog(allowTheOneSelection: Boolean) {
        if (personRegistrationDialogShowing || isFinishing || isDestroyed) return
        personRegistrationDialogShowing = true

        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val nameInput = EditText(this).apply {
            hint = "Jouw naam"
            maxLines = 1
            isSingleLine = true
        }
        container.addView(nameInput)

        val theOneCheck = if (allowTheOneSelection) {
            CheckBox(this).apply {
                text = "Ik ben The One"
                setPadding(0, pad / 2, 0, 0)
                container.addView(this)
            }
        } else {
            null
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Wie gebruikt The One?")
            .setMessage(
                "Vul één keer je naam in. Deze naam wordt aan dit apparaat gekoppeld, " +
                    "zodat de beheerder kan zien van wie een apparaat is."
            )
            .setView(container)
            .setPositiveButton("Opslaan", null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val personName = nameInput.text.toString().trim()
                if (personName.length < 2) {
                    nameInput.error = "Vul je naam in"
                    return@setOnClickListener
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                val isTheOne = theOneCheck?.isChecked == true
                MainDeviceRegistry.savePersonRegistration(
                    this,
                    personName,
                    isTheOne
                )

                dialog.dismiss()
                refreshTiles()
                // Registration on a fresh non-owner installation must submit
                // an owner-approval request even while Main enforcement is OFF.
                // Existing installations with an existing person profile do not
                // enter this flow, avoiding a request storm from legacy updates.
                if (!isTheOne && !MainDeviceRegistry.isLocallyOwner(this)) {
                    Thread {
                        runCatching { MainLicenseClient.askForAccess(this) }
                    }.start()
                }
                // Licensing must run before the first heartbeat, including
                // a newly registered owner device.
                checkDeviceAccess()
            }
        }

        dialog.setOnDismissListener {
            personRegistrationDialogShowing = false
        }
        dialog.show()
    }

    private fun checkDeviceAccess() {
        // Check license BEFORE registration or owner-claiming. An unlicensed
        // clean reinstall must not recreate the old Main device registration.
        Thread {
            val license = MainLicenseClient.status(this)
            val pilotApprovalRequired = MainLicenseClient.needsApprovalBeforeEnforcement(this)
            if (!license.allowed || pilotApprovalRequired) {
                // New Main installations request owner approval even during the
                // pilot, while the production enforcement switch remains OFF.
                // Owner and pre-pilot installations do not enter this path.
                if (pilotApprovalRequired) {
                    runCatching { MainLicenseClient.askForAccess(this) }
                }
                runOnUiThread {
                    findViewById<RecyclerView>(R.id.homeGrid).visibility = View.INVISIBLE
                    if (pilotApprovalRequired) {
                        showMainLicenseDialog("pilot_owner_approval")
                    } else if (license.enabled) {
                        showMainLicenseDialog(license.mode)
                    }
                }
                return@Thread
            }
            val blocked = runCatching { MainDeviceRegistry.heartbeat(this) }.getOrNull()
            if (
                blocked != true &&
                !MainDeviceRegistry.isLocallyOwner(this) &&
                MainDeviceRegistry.isOwnerEligible() &&
                MainDeviceRegistry.isTheOneProfile(this)
            ) {
                runCatching { MainDeviceRegistry.claimInitialOwner(this) }
            }
            runOnUiThread {
                findViewById<RecyclerView>(R.id.homeGrid).visibility = View.VISIBLE
                refreshTiles()
                if (blocked == true || MainDeviceRegistry.isLocallyBlocked(this)) {
                    showBlockedDeviceDialog()
                }
            }
        }.start()
    }

    private fun showMainLicenseDialog(reason: String) {
        if (mainLicenseDialogShowing || isFinishing || isDestroyed) return
        mainLicenseDialogShowing = true
        val pilot = reason == "pilot_owner_approval"
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val explanation = TextView(this).apply {
            text = if (pilot) {
                "Dit apparaat is nieuw en wacht op goedkeuring van de eigenaar van The One. De aanmelding is automatisch aangevraagd. Je kunt hier controleren of de eigenaar je toegang heeft gegeven."
            } else if (reason == "server_offline") {
                "De licentieserver is momenteel niet bereikbaar. Een nieuwe installatie moet online worden geactiveerd. Bestaande geactiveerde apparaten behouden tijdelijk offline toegang."
            } else {
                "Deze nieuwe installatie van The One Main moet eerst door de beheerder worden goedgekeurd. Dit geldt ook na verwijderen en opnieuw installeren op een eerder gebruikt toestel."
            }
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.text_main))
        }
        container.addView(explanation)
        val codeInput = EditText(this).apply {
            hint = "Activatiecode uit The One Main"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(pad / 2, pad, pad / 2, pad)
        }
        if (!pilot) container.addView(codeInput)
        val status = TextView(this).apply {
            text = if (pilot) "Wacht op toestemming. De eigenaar ziet de aanvraag in Main → Meldingen." else "Vraag een code aan bij de beheerder of vul je activatiecode in."
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.text_dim))
        }
        container.addView(status)

        val dialog = AlertDialog.Builder(this)
            .setTitle("The One Main — activatie vereist")
            .setView(container)
            .setPositiveButton(if (pilot) "Goedkeuring controleren" else "Activeren", null)
            .setNegativeButton(if (pilot) "Opnieuw aanvragen" else "Toegang aanvragen", null)
            .setNeutralButton("App sluiten") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val activate = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val request = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            fun setBusy(busy: Boolean) {
                activate.isEnabled = !busy
                request.isEnabled = !busy
            }
            activate.setOnClickListener {
                if (pilot) {
                    setBusy(true)
                    status.text = "Goedkeuring controleren…"
                    Thread {
                        val state = MainLicenseClient.status(this)
                        val stillPending = MainLicenseClient.needsApprovalBeforeEnforcement(this)
                        runOnUiThread {
                            setBusy(false)
                            if (state.allowed && !stillPending) {
                                dialog.dismiss()
                                checkDeviceAccess()
                            } else {
                                status.text = "Nog niet goedgekeurd. De aanvraag blijft bij de eigenaar staan."
                            }
                        }
                    }.start()
                    return@setOnClickListener
                }
                val code = codeInput.text.toString().trim()
                if (code.isBlank()) {
                    status.text = "Vul eerst je activatiecode in."
                    return@setOnClickListener
                }
                setBusy(true)
                status.text = "Code controleren…"
                Thread {
                    val result = runCatching { MainLicenseClient.redeem(this, code) }
                    runOnUiThread {
                        setBusy(false)
                        if (result.getOrNull()?.allowed == true) {
                            dialog.dismiss()
                            Toast.makeText(this, "The One Main is geactiveerd.", Toast.LENGTH_LONG).show()
                            checkDeviceAccess()
                        } else {
                            status.text = "Code niet geldig of server tijdelijk onbereikbaar. Probeer opnieuw."
                        }
                    }
                }.start()
            }
            request.setOnClickListener {
                setBusy(true)
                status.text = "Aanvraag versturen…"
                Thread {
                    val result = runCatching { MainLicenseClient.askForAccess(this) }
                    runOnUiThread {
                        setBusy(false)
                        status.text = if (result.getOrNull()?.pending == true)
                            "Aanvraag verzonden. De eigenaar kan hem zien in Main → Meldingen."
                        else "Verbinding mislukt. Probeer later opnieuw."
                    }
                }.start()
            }
        }
        dialog.setOnDismissListener { mainLicenseDialogShowing = false }
        dialog.show()
    }

    private fun showBlockedDeviceDialog() {
        if (blockedDialogShowing || isFinishing || isDestroyed) return
        blockedDialogShowing = true

        val dialog = AlertDialog.Builder(this)
            .setTitle("Apparaat geblokkeerd")
            .setMessage("Dit apparaat is geblokkeerd voor The One Main. Deblokkeer het vanaf een ander toegestaan apparaat en controleer daarna opnieuw.")
            .setNegativeButton("App sluiten") { _, _ -> finishAffinity() }
            .setPositiveButton("Opnieuw controleren", null)
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val blocked = runCatching { MainDeviceRegistry.heartbeat(this) }.getOrDefault(true)
                    runOnUiThread {
                        if (!blocked) {
                            dialog.dismiss()
                            Toast.makeText(this, "Apparaat is weer vrijgegeven.", Toast.LENGTH_SHORT).show()
                        } else {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }.start()
            }
        }
        dialog.setOnDismissListener { blockedDialogShowing = false }
        dialog.show()
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

    private fun openRun() {
        if (!MainDeviceRegistry.hasAccess(this, MainDeviceRegistry.ACCESS_RUN)) {
            Toast.makeText(this, "The One Run is alleen beschikbaar met toestemming van de eigenaar.", Toast.LENGTH_LONG).show()
            refreshTiles()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage("com.gmailorg.runcoach")
        if (launch != null) {
            launch.flags = launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
            launch.putExtra("the_one_main_device_id", MainDeviceRegistry.deviceId(this))
            @Suppress("DEPRECATION")
            startActivityForResult(launch, 9401)
        }
        else AlertDialog.Builder(this)
            .setTitle("The One Run installeren")
            .setMessage("Installeer de aparte The One Run-app. Daarna opent deze vaste tegel de app.")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Download") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Rubenvaggelen/apps/releases?q=run-v")))
            }.show()
    }

    private fun openDj() {
        if (!MainDeviceRegistry.hasAccess(this, MainDeviceRegistry.ACCESS_DJ)) {
            Toast.makeText(this, "DJ is alleen beschikbaar met toestemming van de beheerder.", Toast.LENGTH_LONG).show()
            refreshTiles()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage("com.gmailorg.thedj")
        if (launch != null) startActivity(launch)
        else AlertDialog.Builder(this)
            .setTitle("The One DJ installeren")
            .setMessage("Installeer de aparte DJ-app met Auto DJ en Voice Sync. Daarna opent deze tegel DJ.")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Download") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Rubenvaggelen/apps/releases?q=dj-v")))
            }.show()
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

        val iconDrawable = if (tile.id == "com.gmailorg.thedj") ContextCompat.getDrawable(context, R.drawable.the_one_dj_logo) else if (tile.id == "com.gmailorg.runcoach") {
            try {
                context.packageManager.getApplicationIcon("com.gmailorg.runcoach")
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                ContextCompat.getDrawable(context, R.drawable.the_one_run_icon)
            }
        } else when (tile.type) {
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
            TileType.FINANCE -> ContextCompat.getDrawable(context, R.drawable.ic_home_currency_fancy)
            TileType.LIFESTYLE -> ContextCompat.getDrawable(context, R.drawable.ic_home_lifestyle_fancy)
            TileType.FITNESS -> ContextCompat.getDrawable(context, R.drawable.ic_home_fitness_fancy)
            TileType.REMOTE_PC -> ContextCompat.getDrawable(context, R.drawable.ic_home_remote_pc_fancy)
            TileType.ADD_BUTTON -> ContextCompat.getDrawable(context, R.drawable.ic_home_add_fancy)
            TileType.APP -> try {
                buildBadgedAppIcon(context, context.packageManager.getApplicationIcon(tile.packageName!!))
            } catch (e: Exception) {
                ContextCompat.getDrawable(context, R.drawable.ic_home_add_fancy)
            }
        }
        val styledIcon = if (tile.id in setOf("com.gmailorg.thedj", "com.gmailorg.runcoach") && iconDrawable != null) {
            buildTheOneAppTileIcon(context, iconDrawable)
        } else iconDrawable
        holder.icon.setImageDrawable(styledIcon)

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
        // Zelfde verhouding als de glyphs in de vaste badges (~31dp icoon
        // gecentreerd in een 56dp tegel, dus ~12-13dp inspringen rondom).
        val inset = (13 * context.resources.displayMetrics.density).toInt()
        val layered = LayerDrawable(arrayOf(badge, appIcon))
        layered.setLayerInset(1, inset, inset, inset, inset)
        return layered
    }

    private fun buildTheOneAppTileIcon(context: Context, appIcon: Drawable): Drawable {
        val density = context.resources.displayMetrics.density
        val badge = ContextCompat.getDrawable(context, R.drawable.bg_home_tile_badge)!!.mutate()
        // Keep the official DJ and Run artwork intact, while clipping square
        // launcher backgrounds to the circular Main tile badge.
        return CircularAppTileBadge(
            badge = badge,
            appIcon = appIcon,
            insetPx = (13 * density).toInt(),
            sizePx = (56 * density).toInt()
        )
    }

    private class CircularAppTileBadge(
        private val badge: Drawable,
        private val appIcon: Drawable,
        private val insetPx: Int,
        private val sizePx: Int
    ) : Drawable() {
        private val clipPath = Path()
        private val clipBounds = RectF()

        override fun draw(canvas: Canvas) {
            val outer = bounds
            if (outer.width() <= 0 || outer.height() <= 0) return

            badge.bounds = outer
            badge.draw(canvas)

            clipBounds.set(
                (outer.left + insetPx).toFloat(),
                (outer.top + insetPx).toFloat(),
                (outer.right - insetPx).toFloat(),
                (outer.bottom - insetPx).toFloat()
            )
            clipPath.reset()
            clipPath.addOval(clipBounds, Path.Direction.CW)

            val save = canvas.save()
            canvas.clipPath(clipPath)
            appIcon.bounds = android.graphics.Rect(
                clipBounds.left.toInt(),
                clipBounds.top.toInt(),
                clipBounds.right.toInt(),
                clipBounds.bottom.toInt()
            )
            appIcon.draw(canvas)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            badge.alpha = alpha
            appIcon.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            badge.colorFilter = colorFilter
            appIcon.colorFilter = colorFilter
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
    }
}
