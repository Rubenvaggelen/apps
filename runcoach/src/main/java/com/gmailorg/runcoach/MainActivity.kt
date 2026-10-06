package com.gmailorg.runcoach

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 7
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        // The One-huisstijl (zelfde palet als The One en The One DJ)
        private val BG = Color.parseColor("#071019")
        private val CARD = Color.parseColor("#0D1A28")
        private val LINE = Color.parseColor("#174963")
        private val ACCENT = Color.parseColor("#20B8FF")
        private val ACCENT_HI = Color.parseColor("#58D0FF")
        private val ACCENT_LO = Color.parseColor("#1590C9")
        private val ACCENT_EDGE = Color.parseColor("#8FE0FF")
        private val ACCENT_INK = Color.parseColor("#041522")
        private val TEXT = Color.parseColor("#F3F8FC")
        private val MUTED = Color.parseColor("#91A4BD")
        private val GOOD = Color.parseColor("#39D98A")
        private val WARN = Color.parseColor("#E8AA4E")
        private val DANGER = Color.parseColor("#C62828")
    }


    private var accessReady = false
    private var accessBusy = false
    private var nameDialogShowing = false
    private val accessPoll = android.os.Handler(android.os.Looper.getMainLooper())
    private val accessTick = object : Runnable {
        override fun run() { checkRunAccess(false) }
    }

    private fun checkRunAccess(ask: Boolean) {
        if (accessBusy || isFinishing || isDestroyed) return
        if (RunAccessRegistry.name(this).isBlank()) {
            showNameRegistration()
            return
        }
        accessBusy = true
        Thread {
            val result = runCatching { RunAccessRegistry.check(this, ask) }
            runOnUiThread {
                accessBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val status = result.getOrNull()
                accessReady = status?.allowed == true
                shownScreen = ""
                if (accessReady) render(RunRepository.snapshot)
                else showAccessGate(when {
                    status == null -> "Verbinding mislukt. Controleer je internet en probeer opnieuw."
                    status.blocked -> "Dit apparaat is geblokkeerd. De eigenaar moet het vrijgeven."
                    status.pending -> "Je aanvraag is verstuurd. Wacht op toestemming van The One."
                    else -> "Vraag The One om toestemming om deze app te gebruiken."
                })
                accessPoll.removeCallbacks(accessTick)
                if (!accessReady && status?.pending == true) accessPoll.postDelayed(accessTick, 15000)
            }
        }.start()
    }

    private fun showNameRegistration() {
        showAccessGate("Vul eerst je naam in en vraag toestemming.")
        if (nameDialogShowing) return
        nameDialogShowing = true
        val input = android.widget.EditText(this).apply {
            hint = "Jouw naam"
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Wie gebruikt The One Run?")
            .setMessage("Vul je naam in. The One moet daarna je toegang goedkeuren.")
            .setView(input)
            .setPositiveButton("Toestemming vragen", null)
            .setNegativeButton("Sluiten") { _, _ -> finish() }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.length < 2) input.error = "Vul je naam in"
                else {
                    RunAccessRegistry.saveName(this, name)
                    dialog.dismiss()
                    checkRunAccess(true)
                }
            }
        }
        dialog.setOnDismissListener { nameDialogShowing = false }
        dialog.show()
    }

    private fun showAccessGate(message: String) {
        root.removeAllViews()
        root.addView(header())
        root.addView(tv(message, 17f, TEXT), lp(top = 24))
        root.addView(button("Toegang controleren / aanvragen", ACCENT).apply {
            setOnClickListener { checkRunAccess(true) }
        }, lp(top = 24))
    }

    private val updater by lazy { RunUpdater(this) }
    private val prefs by lazy { getSharedPreferences("runcoach", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var shownScreen = ""

    // instellingen
    private var treadmillMode = false
    private var beltSpeedKmh = 6.0
    private var beltSpeedText: TextView? = null
    private var targetKm = 0
    private var goalOn = false
    private var goalPace = 360
    private var level = 1
    private var voice = Coach.VOICE_NORMAL
    private var voiceMale = false

    // geschiedenis: "" = startscherm, "history" = lijst, "detail" = één training
    private var page = ""
    private var detail: Session? = null
    private var preview: Speaker? = null
    private val dateFmt = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.forLanguageTag("nl-NL"))

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

        treadmillMode = prefs.getBoolean("treadmill", false)
        beltSpeedKmh = prefs.getFloat("beltSpeed", 6f).toDouble().coerceIn(0.0, 40.0)
        targetKm = prefs.getInt("targetKm", 0)
        goalOn = prefs.getBoolean("goalOn", false)
        goalPace = prefs.getInt("goalPace", 360)
        level = prefs.getInt("level", 1)
        voice = prefs.getInt("voice", Coach.VOICE_NORMAL)
        voiceMale = prefs.getBoolean("voiceMale", false)

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
        RunAccessRegistry.bindFromMain(this)
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
        RunRepository.addListener(listener)
        accessReady = false
        showAccessGate("Toegang controleren…")
        checkRunAccess(false)
    }

    override fun onPause() {
        updater.onPause()
        accessPoll.removeCallbacks(accessTick)
        RunRepository.removeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        updater.close()
        preview?.shutdown()
        preview = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (page) {
            "detail" -> open("history")
            "history" -> open("")
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ render

    private fun render(s: RunSnapshot) {
        if (!accessReady) return
        val screen = when (s.status) {
            RunStatus.IDLE -> if (page.isEmpty()) "setup" else page
            RunStatus.FINISHED -> "summary"
            else -> "run"
        }
        if (s.status != RunStatus.IDLE) page = ""
        if (screen != shownScreen) {
            shownScreen = screen
            root.removeAllViews()
            when (screen) {
                "setup" -> buildSetup()
                "run" -> buildRun()
                "history" -> buildHistory()
                "detail" -> buildDetail()
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

    private fun open(p: String) {
        page = p
        shownScreen = ""
        render(RunRepository.snapshot)
        (root.parent as? ScrollView)?.scrollTo(0, 0)
    }

    private fun save() {
        prefs.edit()
            .putBoolean("treadmill", treadmillMode)
            .putFloat("beltSpeed", beltSpeedKmh.toFloat())
            .putInt("targetKm", targetKm)
            .putBoolean("goalOn", goalOn)
            .putInt("goalPace", goalPace)
            .putInt("level", level)
            .putInt("voice", voice)
            .putBoolean("voiceMale", voiceMale)
            .apply()
    }

    // ------------------------------------------------------------------ startscherm

    private fun buildSetup() {
        root.addView(header())
        addSection("Waar loop je?")
        root.addView(chipRow(listOf("Buiten" to !treadmillMode, "Loopband" to treadmillMode)) { i ->
            treadmillMode = i == 1
            refreshSetup()
        }, lp(top = 8))
        if (treadmillMode) {
            addBeltSpeedControls(beltSpeedKmh) { speed -> beltSpeedKmh = speed; save() }
            root.addView(tv("Voer dezelfde snelheid in als op je loopband. Afstand en tempo worden berekend. Pauzeer Run wanneer je de band pauzeert.", 13f, MUTED), lp(top = 8))
            root.addView(tv("Stappen meten: draag je telefoon bij je. Op de console worden jouw stappen niet betrouwbaar geteld.", 13f, MUTED), lp(top = 6))
        }

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
        root.addView(dropdown(listOf("Stil", "Normaal", "Veel"), level) { i ->
            level = i
            refreshSetup()
        }, lp(top = 8))
        val expl = when (level) {
            0 -> "Alleen een gesproken update bij elke kilometer."
            1 -> "Kilometerupdates en correcties als je te snel of te langzaam loopt."
            else -> "Alles van Normaal, plus elke 500 meter tempo-info en motivatie."
        }
        root.addView(tv(expl, 13f, MUTED), lp(top = 6))
        if (level >= 1 && !goalOn) {
            root.addView(tv("Tip: zet een doeltempo aan, dan kan de coach je tempo corrigeren.", 13f, WARN), lp(top = 4))
        }

        addSection("Stem van de coach")
        root.addView(dropdown(listOf("Rustig", "Normaal", "Streng"), voice) { i ->
            voice = i
            refreshSetup()
            playSample()
        }, lp(top = 8))
        root.addView(dropdown(listOf("Vrouwenstem", "Mannenstem"), if (voiceMale) 1 else 0) { i ->
            voiceMale = i == 1
            refreshSetup()
            playSample()
        }, lp(top = 8))
        val voiceExpl = when (voice) {
            Coach.VOICE_CALM -> "Rustige, vriendelijke stem die je ontspannen laat lopen."
            Coach.VOICE_STRICT -> "Strenge motivator: lage, snelle stem die je bij elke kilometer aanpakt en geen excuses accepteert."
            else -> "Duidelijke coach: heldere updates over afstand en tempo."
        }
        root.addView(tv("$voiceExpl Tik op een stem om hem te horen.", 13f, MUTED), lp(top = 6))
        if (voice == Coach.VOICE_STRICT && level == 0) {
            root.addView(tv("Tip: zet het coachniveau op Normaal of Veel, anders houdt de motivator zich in.", 13f, WARN), lp(top = 4))
        }

        val start = button("START HARDLOPEN", ACCENT, size = 22f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(22), dp(8), dp(22))
            setOnClickListener { onStartClicked() }
        }
        root.addView(start, lp(top = 32))

        val history = button("GESCHIEDENIS", CARD, size = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { open("history") }
        }
        root.addView(history, lp(top = 12))

        root.addView(button("Updates controleren", CARD).apply {
            setOnClickListener { updater.check(true) }
        }, lp(top = 12))
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.the_one_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            contentDescription = "The One"
        }
        root.addView(
            logo,
            LinearLayout.LayoutParams(dp(120), dp(120)).apply {
                topMargin = dp(28)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
    }


    private fun addBeltSpeedControls(initial: Double, changed: (Double) -> Unit) {
        var speed = initial
        val label = tv("Bandsnelheid: ${Fmt.speed(speed)} km/u", 22f, ACCENT, true)
        beltSpeedText = label
        root.addView(label, lp(top = 16))
        val controls = row()
        fun applySpeed(value: Double) {
            speed = (kotlin.math.round(value * 10) / 10).coerceIn(0.0, 40.0)
            label.text = "Bandsnelheid: ${Fmt.speed(speed)} km/u"
            changed(speed)
        }
        controls.addView(button("− 0,1", CARD).apply {
            setOnClickListener { applySpeed(speed - 0.1) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        controls.addView(button("Invullen", CARD).apply {
            setOnClickListener {
                val input = android.widget.EditText(this@MainActivity).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(Fmt.speed(speed))
                    selectAll()
                }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Snelheid op je loopband (km/u)")
                    .setView(input).setNegativeButton("Annuleren", null)
                    .setPositiveButton("Opslaan", null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = input.text.toString().replace(',', '.').toDoubleOrNull()
                        if (value == null || !value.isFinite() || value !in 0.0..40.0) input.error = "Vul 0 tot 40 km/u in"
                        else { applySpeed(value); dialog.dismiss() }
                    }
                }
                dialog.show()
            }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        controls.addView(button("+ 0,1", CARD).apply {
            setOnClickListener { applySpeed(speed + 0.1) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(controls, lp(top = 8))
    }

    private fun playSample() {
        val sp = preview ?: Speaker(this).also { preview = it }
        sp.setVoice(voice, voiceMale)
        sp.say(Coach.voiceSample(voice))
    }

    // ------------------------------------------------------------------ live-scherm

    private fun buildRun() {
        val running = RunRepository.snapshot
        if (running.treadmill) {
            addBeltSpeedControls(running.treadmillSpeedKmh ?: beltSpeedKmh) { speed ->
                beltSpeedKmh = speed
                save()
                RunService.speed(this, speed)
            }
        }
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
        if (s.treadmill) beltSpeedText?.text = "Bandsnelheid: ${Fmt.speed(s.treadmillSpeedKmh)} km/u"
        tvStatus.text = when (s.status) {
            RunStatus.WAITING_GPS -> "GPS zoeken…" + (acc?.let { " (±$it m)" } ?: "")
            RunStatus.PAUSED -> "Gepauzeerd"
            else -> if (s.treadmill) "Loopband · afstand berekend" else "Bezig" + (acc?.let { " · GPS ±$it m" } ?: "")
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
        addStats(s)
        if (s.distanceM >= RunService.MIN_SAVE_DISTANCE_M) {
            root.addView(tv("Opgeslagen in je geschiedenis.", 13f, MUTED), lp(top = 14))
        }

        val again = button("NIEUWE TRAINING", ACCENT, size = 20f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(18), dp(8), dp(18))
            setOnClickListener { RunRepository.update(RunSnapshot()) }
        }
        root.addView(again, lp(top = 28))

        val history = button("GESCHIEDENIS", CARD, size = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                page = "history"
                RunRepository.update(RunSnapshot())
            }
        }
        root.addView(history, lp(top = 12))
    }

    /** Statistieken en kilometertijden van één training (samenvatting én geschiedenis). */
    private fun addStats(s: RunSnapshot) {
        fun line(label: String, value: String, color: Int = TEXT) {
            val r = row().apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(tv(label, 16f, MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(tv(value, 18f, color, true))
            root.addView(r, lp(top = 10))
        }

        line("Training", if (s.treadmill) "Loopband · berekend" else "Buiten")
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
    }

    // ------------------------------------------------------------------ geschiedenis

    private fun buildHistory() {
        root.addView(header())
        root.addView(tv("Geschiedenis", 28f, TEXT, true), lp(top = 20))

        val sessions = History.load(this)
        if (sessions.isEmpty()) {
            root.addView(
                tv("Nog geen trainingen opgeslagen. Elke training van minstens 50 meter komt hier vanzelf te staan.", 14f, MUTED),
                lp(top = 8)
            )
        } else {
            root.addView(tv("Tik op een training voor alle details.", 13f, MUTED), lp(top = 6))
        }

        sessions.forEach { se ->
            val card = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, 16)
                setPadding(dp(14), dp(10), dp(10), dp(10))
                setOnClickListener {
                    detail = se
                    open("detail")
                }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(tv(dateFmt.format(Date(se.id)), 15f, TEXT, true))
            info.addView(
                tv(
                    "${Fmt.km(se.snap.distanceM)} km  •  ${Fmt.time(se.snap.elapsedMs)}  •  ${Fmt.pace(se.snap.avgPaceSecPerKm)} /km",
                    14f, MUTED
                ),
                lp(top = 2)
            )
            card.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
            val del = button("Wis", DANGER, size = 14f)
            del.setOnClickListener { confirmDelete(se) }
            card.addView(del, LinearLayout.LayoutParams(dp(72), WRAP).apply { marginStart = dp(8) })
            root.addView(card, lp(top = 10))
        }

        if (sessions.isNotEmpty()) {
            val clear = button("ALLES WISSEN", DANGER, size = 16f).apply {
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { confirmClear(sessions.size) }
            }
            root.addView(clear, lp(top = 24))
        }
        val back = button("TERUG", CARD, size = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { open("") }
        }
        root.addView(back, lp(top = 12))
    }

    private fun buildDetail() {
        val se = detail
        if (se == null) {
            open("history")
            return
        }
        root.addView(header())
        root.addView(tv(dateFmt.format(Date(se.id)), 22f, TEXT, true), lp(top = 20))
        val extra = listOfNotNull(
            se.snap.targetDistanceM?.let { "Doel ${Fmt.km(it)} km" },
            se.snap.goalPaceSecPerKm?.let { "Doeltempo ${Fmt.pace(it.toDouble())} /km" }
        )
        if (extra.isNotEmpty()) root.addView(tv(extra.joinToString("  •  "), 13f, MUTED), lp(top = 4))
        addStats(se.snap)

        val del = button("WIS DEZE TRAINING", DANGER, size = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { confirmDelete(se) }
        }
        root.addView(del, lp(top = 28))
        val back = button("TERUG", CARD, size = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { open("history") }
        }
        root.addView(back, lp(top = 12))
    }

    private fun confirmDelete(se: Session) {
        AlertDialog.Builder(this)
            .setTitle("Training wissen?")
            .setMessage("${dateFmt.format(Date(se.id))} (${Fmt.km(se.snap.distanceM)} km) wordt definitief verwijderd.")
            .setPositiveButton("Wissen") { _, _ ->
                History.delete(this, se.id)
                detail = null
                open("history")
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun confirmClear(count: Int) {
        AlertDialog.Builder(this)
            .setTitle("Alles wissen?")
            .setMessage("Alle $count opgeslagen trainingen worden definitief verwijderd.")
            .setPositiveButton("Alles wissen") { _, _ ->
                History.clear(this)
                detail = null
                open("history")
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    // ------------------------------------------------------------------ starten + toestemmingen

    private fun onStartClicked() {
        val needed = mutableListOf<String>()
        if (!treadmillMode) needed += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) needed += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        else checkBatteryThenStart()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (treadmillMode || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
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
        RunService.start(this, target, goal, level, voice, voiceMale, treadmillMode, beltSpeedKmh)
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

    /** Vlakken in huisstijl: accent = blauw verloop met lichte rand, paneel = donker met lijnrand. */
    private fun rounded(color: Int, radiusDp: Int = 14) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        when (color) {
            ACCENT -> {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(ACCENT_HI, ACCENT_LO)
                setStroke(dp(1), ACCENT_EDGE)
            }
            CARD -> {
                setColor(color)
                setStroke(dp(1), LINE)
            }
            else -> setColor(color)
        }
    }

    private fun button(text: String, bg: Int, fg: Int = if (bg == ACCENT) ACCENT_INK else TEXT, size: Float = 16f) = Button(this).apply {
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
        text.addView(tv("The One Run", 30f, TEXT).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) })
        text.addView(tv("PART OF THE ONE FAMILY", 11f, ACCENT, true).apply { letterSpacing = 0.16f })
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }
    }

    private fun addSection(title: String) {
        root.addView(tv(title.uppercase(), 13f, MUTED, true), lp(top = 24))
    }

    private fun dropdown(options: List<String>, selected: Int, changed: (Int) -> Unit): android.widget.Spinner {
        val initial = selected.coerceIn(options.indices)
        return android.widget.Spinner(this, android.widget.Spinner.MODE_DROPDOWN).apply {
            background = rounded(CARD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            adapter = object : android.widget.ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, options) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    tv(options[position] + "  ▾", 17f, TEXT).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    tv(options[position], 17f, TEXT).apply {
                        setBackgroundColor(CARD)
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                    }
            }
            setSelection(initial, false)
            var current = initial
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != current) { current = position; changed(position) }
                }
            }
        }
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
