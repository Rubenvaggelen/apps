package com.gmailorg.runcoach

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 7
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val BG = Color.parseColor("#0E0E12")
        private val CARD = Color.parseColor("#1C1C24")
        private val ACCENT = Color.parseColor("#FF6A3D")
        private val TEXT = Color.WHITE
        private val MUTED = Color.parseColor("#9A9AA8")
        private val GOOD = Color.parseColor("#3DDC84")
        private val WARN = Color.parseColor("#FFB020")
        private val DANGER = Color.parseColor("#C62828")
    }

    private val prefs by lazy { getSharedPreferences("runcoach", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var shownScreen = ""

    // instellingen
    private var targetKm = 0
    private var goalOn = false
    private var goalPace = 360
    private var level = 1

    // live-scherm
    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvPace: TextView
    private lateinit var tvGoal: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvSteps: TextView
    private lateinit var tvAvg: TextView
    private lateinit var tvCadence: TextView
    private lateinit var btnPause: Button
    private lateinit var btnStartNow: Button

    private val listener: (RunSnapshot) -> Unit = { render(it) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        targetKm = prefs.getInt("targetKm", 0)
        goalOn = prefs.getBoolean("goalOn", false)
        goalPace = prefs.getInt("goalPace", 360)
        level = prefs.getInt("level", 1)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                top = b.top
                bottom = b.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        RunRepository.addListener(listener)
        render(RunRepository.snapshot)
    }

    override fun onPause() {
        RunRepository.removeListener(listener)
        super.onPause()
    }

    // ------------------------------------------------------------------ render

    private fun render(s: RunSnapshot) {
        val screen = when (s.status) {
            RunStatus.IDLE -> "setup"
            RunStatus.FINISHED -> "summary"
            else -> "run"
        }
        if (screen != shownScreen) {
            shownScreen = screen
            root.removeAllViews()
            when (screen) {
                "setup" -> buildSetup()
                "run" -> buildRun()
                else -> buildSummary(s)
            }
        }
        if (screen == "run") updateRun(s)
    }

    private fun refreshSetup() {
        save()
        shownScreen = ""
        render(RunRepository.snapshot)
    }

    private fun save() {
        prefs.edit()
            .putInt("targetKm", targetKm)
            .putBoolean("goalOn", goalOn)
            .putInt("goalPace", goalPace)
            .putInt("level", level)
            .apply()
    }

    // ------------------------------------------------------------------ startscherm

    private fun buildSetup() {
        root.addView(header())

        addSection("Doel")
        root.addView(
            chipRow(listOf("Vrij lopen" to (targetKm == 0), "5 km" to (targetKm == 5), "10 km" to (targetKm == 10))) { i ->
                targetKm = listOf(0, 5, 10)[i]
                refreshSetup()
            }, lp(top = 8)
        )

        addSection("Doeltempo")
        root.addView(
            chipRow(listOf("Uit" to !goalOn, "Aan" to goalOn)) { i ->
                goalOn = i == 1
                refreshSetup()
            }, lp(top = 8)
        )
        if (goalOn) {
            val r = row().apply { gravity = Gravity.CENTER_VERTICAL }
            val value = tv(Fmt.pace(goalPace.toDouble()) + " /km", 34f, TEXT, true, Gravity.CENTER)
            val minus = button("−", CARD, size = 26f)
            val plus = button("+", CARD, size = 26f)
            minus.setOnClickListener {
                goalPace = (goalPace - 5).coerceAtLeast(180)
                value.text = Fmt.pace(goalPace.toDouble()) + " /km"
                save()
            }
            plus.setOnClickListener {
                goalPace = (goalPace + 5).coerceAtMost(900)
                value.text = Fmt.pace(goalPace.toDouble()) + " /km"
                save()
            }
            r.addView(minus, LinearLayout.LayoutParams(dp(68), WRAP))
            r.addView(value, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(plus, LinearLayout.LayoutParams(dp(68), WRAP))
            root.addView(r, lp(top = 12))
            root.addView(
                tv("De coach grijpt in als je minstens 12 sec/km afwijkt, ongeveer 20 seconden lang.", 13f, MUTED),
                lp(top = 6)
            )
        }

        addSection("Coachniveau")
        root.addView(
            chipRow(listOf("Stil" to (level == 0), "Normaal" to (level == 1), "Veel" to (level == 2))) { i ->
                level = i
                refreshSetup()
            }, lp(top = 8)
        )
        val expl = when (level) {
            0 -> "Alleen een gesproken update bij elke kilometer."
            1 -> "Kilometerupdates en correcties als je te snel of te langzaam loopt."
            else -> "Alles van Normaal, plus elke 500 meter tempo-info en motivatie."
        }
        root.addView(tv(expl, 13f, MUTED), lp(top = 6))
        if (level >= 1 && !goalOn) {
            root.addView(tv("Tip: zet een doeltempo aan, dan kan de coach je tempo corrigeren.", 13f, WARN), lp(top = 4))
        }

        val start = button("START HARDLOPEN", ACCENT, size = 22f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(22), dp(8), dp(22))
            setOnClickListener { onStartClicked() }
        }
        root.addView(start, lp(top = 32))
    }

    // ------------------------------------------------------------------ live-scherm

    private fun buildRun() {
        tvStatus = tv("", 15f, ACCENT, true)
        root.addView(tvStatus)

        tvDistance = tv("0,00", 88f, TEXT, true, Gravity.CENTER)
        root.addView(tvDistance, lp(top = 8))
        root.addView(tv("kilometer", 16f, MUTED, gravity = Gravity.CENTER))
        tvTarget = tv("", 14f, MUTED, gravity = Gravity.CENTER)
        root.addView(tvTarget, lp(top = 4))

        val pace = statBox("Huidig tempo /km")
        val goal = statBox("Doeltempo /km")
        val time = statBox("Tijd")
        val stepsBox = statBox("Stappen")
        val avg = statBox("Gem. tempo /km")
        val cad = statBox("Cadans")
        tvPace = pace.second; tvGoal = goal.second
        tvTime = time.second; tvSteps = stepsBox.second
        tvAvg = avg.second; tvCadence = cad.second
        root.addView(statRow(pace, goal), lp(top = 20))
        root.addView(statRow(time, stepsBox), lp(top = 10))
        root.addView(statRow(avg, cad), lp(top = 10))

        btnStartNow = button("Nu starten zonder goede GPS-fix", CARD, size = 15f)
        btnStartNow.setOnClickListener { RunService.action(this, RunService.ACTION_START_NOW) }
        root.addView(btnStartNow, lp(top = 18))

        val r = row()
        btnPause = button("PAUZE", CARD, size = 18f).apply { typeface = Typeface.DEFAULT_BOLD }
        btnPause.setOnClickListener {
            val st = RunRepository.snapshot.status
            RunService.action(
                this,
                if (st == RunStatus.PAUSED) RunService.ACTION_RESUME else RunService.ACTION_PAUSE
            )
        }
        val stop = button("STOP", DANGER, size = 18f).apply { typeface = Typeface.DEFAULT_BOLD }
        stop.setOnClickListener { confirmStop() }
        r.addView(btnPause, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(stop, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        root.addView(r, lp(top = 14))
    }

    private fun updateRun(s: RunSnapshot) {
        val acc = s.gpsAccuracyM?.roundToInt()
        tvStatus.text = when (s.status) {
            RunStatus.WAITING_GPS -> "GPS zoeken…" + (acc?.let { " (±$it m)" } ?: "")
            RunStatus.PAUSED -> "Gepauzeerd"
            else -> "Bezig" + (acc?.let { " · GPS ±$it m" } ?: "")
        }
        tvDistance.text = Fmt.km(s.distanceM)

        val t = s.targetDistanceM
        tvTarget.text = if (t == null) "" else {
            val left = t - s.distanceM
            if (left > 0) "Nog ${Fmt.km(left)} km tot je doel van ${(t / 1000).toInt()} km"
            else "Doel van ${(t / 1000).toInt()} km gehaald!"
        }

        val g = s.goalPaceSecPerKm
        val c = s.currentPaceSecPerKm
        tvPace.text = Fmt.pace(c)
        tvPace.setTextColor(
            when {
                g == null || c == null -> TEXT
                abs(c - g) <= 12 -> GOOD
                else -> WARN
            }
        )
        tvGoal.text = g?.let { Fmt.pace(it.toDouble()) } ?: "—"
        tvTime.text = Fmt.time(s.elapsedMs)
        tvSteps.text = if (s.stepsAvailable) s.steps.toString() else "n.v.t."
        tvAvg.text = Fmt.pace(s.avgPaceSecPerKm)
        tvCadence.text = s.cadence?.let { "$it spm" } ?: "--"

        btnStartNow.visibility = if (s.status == RunStatus.WAITING_GPS) View.VISIBLE else View.GONE
        btnPause.text = if (s.status == RunStatus.PAUSED) "HERVATTEN" else "PAUZE"
        btnPause.isEnabled = s.status != RunStatus.WAITING_GPS
        btnPause.alpha = if (btnPause.isEnabled) 1f else 0.4f
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Training stoppen?")
            .setMessage("Je statistieken blijven zichtbaar tot je een nieuwe training start.")
            .setPositiveButton("Stoppen") { _, _ -> RunService.action(this, RunService.ACTION_STOP) }
            .setNegativeButton("Doorgaan", null)
            .show()
    }

    // ------------------------------------------------------------------ samenvatting

    private fun buildSummary(s: RunSnapshot) {
        root.addView(header())
        root.addView(tv("Training voltooid", 28f, TEXT, true), lp(top = 20))

        fun line(label: String, value: String, color: Int = TEXT) {
            val r = row().apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(tv(label, 16f, MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(tv(value, 18f, color, true))
            root.addView(r, lp(top = 10))
        }

        line("Afstand", "${Fmt.km(s.distanceM)} km")
        line("Totale tijd", Fmt.time(s.elapsedMs))
        line("Gemiddeld tempo", "${Fmt.pace(s.avgPaceSecPerKm)} /km")
        line("Gemiddelde snelheid", "${Fmt.speed(s.avgSpeedKmh)} km/u")
        line("Stappen", if (s.stepsAvailable) s.steps.toString() else "n.v.t.")
        line("Gemiddelde cadans", s.avgCadence?.let { "$it spm" } ?: "--")

        addSection("Kilometertijden")
        val fastest = if (s.splits.size > 1) s.splits.minByOrNull { it.splitMs } else null
        s.splits.forEach { sp ->
            line("Km ${sp.km}", Fmt.time(sp.splitMs), if (sp == fastest) GOOD else TEXT)
        }
        val rest = s.distanceM - s.splits.size * 1000.0
        val lastTotal = s.splits.lastOrNull()?.totalMs ?: 0L
        if (rest >= 50) line("Laatste ${Fmt.km(rest)} km", Fmt.time(s.elapsedMs - lastTotal))
        if (s.splits.isEmpty() && rest < 50) root.addView(tv("Nog geen afstand gemeten.", 14f, MUTED), lp(top = 8))

        val again = button("NIEUWE TRAINING", ACCENT, size = 20f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(18), dp(8), dp(18))
            setOnClickListener { RunRepository.update(RunSnapshot()) }
        }
        root.addView(again, lp(top = 28))
    }

    // ------------------------------------------------------------------ starten + toestemmingen

    private fun onStartClicked() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 29) needed += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        else checkBatteryThenStart()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            checkBatteryThenStart()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Locatie nodig")
                .setMessage("Zonder precieze locatie kan The One Run geen afstand en tempo meten. Geef de toestemming via de app-instellingen.")
                .setPositiveButton("Instellingen") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Annuleren", null)
                .show()
        }
    }

    private fun checkBatteryThenStart() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("batterySkip", false)) {
            AlertDialog.Builder(this)
                .setTitle("Batterijbeheer")
                .setMessage(
                    "Samsung kan apps stilleggen als je scherm uit staat. Zet The One Run op " +
                        "'Niet beperkt', zodat je meting niet halverwege je loop stopt."
                )
                .setPositiveButton("Instellen") { _, _ -> openBatterySettings() }
                .setNegativeButton("Nu niet") { _, _ -> startRun() }
                .setNeutralButton("Niet meer vragen") { _, _ ->
                    prefs.edit().putBoolean("batterySkip", true).apply()
                    startRun()
                }
                .show()
        } else {
            startRun()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun startRun() {
        val target = if (targetKm > 0) targetKm * 1000.0 else null
        val goal = if (goalOn) goalPace else null
        RunService.start(this, target, goal, level)
    }

    // ------------------------------------------------------------------ view-hulpjes

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int = TEXT, bold: Boolean = false, gravity: Int = Gravity.START) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            this.gravity = gravity
        }

    private fun rounded(color: Int, radiusDp: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun button(text: String, bg: Int, fg: Int = Color.WHITE, size: Float = 16f) = Button(this).apply {
        this.text = text
        setTextColor(fg)
        textSize = size
        isAllCaps = false
        background = rounded(bg)
        stateListAnimator = null
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }

    private fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    /** Kop: ronde logo-badge links, daarnaast de appnaam met de familieregel eronder. */
    private fun header(): LinearLayout {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo_badge)
            contentDescription = "The One Run"
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(tv("The One Run", 30f, TEXT, true))
        text.addView(tv("PART OF THE ONE FAMILY", 11f, MUTED, true).apply { letterSpacing = 0.18f })
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }
    }

    private fun addSection(title: String) {
        root.addView(tv(title.uppercase(), 13f, MUTED, true), lp(top = 24))
    }

    private fun chipRow(options: List<Pair<String, Boolean>>, onClick: (Int) -> Unit): LinearLayout {
        val r = row()
        options.forEachIndexed { i, (label, selected) ->
            val b = button(label, if (selected) ACCENT else CARD, size = 15f)
            b.setOnClickListener { onClick(i) }
            r.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        return r
    }

    private fun statBox(label: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(tv(label, 13f, MUTED))
        val value = tv("--", 30f, TEXT, true)
        box.addView(value)
        return box to value
    }

    private fun statRow(a: Pair<LinearLayout, TextView>, b: Pair<LinearLayout, TextView>): LinearLayout {
        val r = row()
        r.addView(a.first, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(b.first, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        return r
    }
}
