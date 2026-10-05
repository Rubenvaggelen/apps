package com.gmailorg.runcoach.wear

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horloge-scherm van The One Run. De telefoon doet het meten en de coach;
 * het horloge toont live afstand/tempo/tijd en kan pauzeren, hervatten en stoppen.
 */
class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {

    companion object {
        private const val PATH_STATE = "/run/state"
        private const val PATH_CMD = "/run/cmd"
        private val NL: Locale = Locale.forLanguageTag("nl-NL")

        private val BG = Color.parseColor("#071019")
        private val CARD = Color.parseColor("#0D1A28")
        private val LINE = Color.parseColor("#174963")
        private val ACCENT = Color.parseColor("#20B8FF")
        private val ACCENT_INK = Color.parseColor("#041522")
        private val TEXT = Color.parseColor("#F3F8FC")
        private val MUTED = Color.parseColor("#91A4BD")
        private val GOOD = Color.parseColor("#3DDC84")
        private val WARN = Color.parseColor("#FFB020")
        private val DANGER = Color.parseColor("#C62828")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var state: JSONObject? = null
    private var lastMsgAt = 0L
    private var lastKm = -1
    private var stopArmedAt = 0L

    private lateinit var logo: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvUnit: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvPace: TextView
    private lateinit var tvExtra: TextView
    private lateinit var btnStartNow: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isVerticalScrollBarEnabled = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(24), dp(26), dp(44))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        logo = ImageView(this).apply { setImageResource(R.drawable.logo_badge) }
        root.addView(logo, LinearLayout.LayoutParams(dp(30), dp(30)))

        tvStatus = tv("Verbinden met telefoon…", 12f, ACCENT, true)
        root.addView(tvStatus, lp(top = 4))
        tvDistance = tv("0,00", 44f, TEXT, true)
        root.addView(tvDistance, lp(top = 2))
        tvUnit = tv("km", 12f, MUTED)
        root.addView(tvUnit)
        tvTime = tv("00:00", 22f, TEXT, true)
        root.addView(tvTime, lp(top = 6))
        tvPace = tv("--:-- /km", 18f, TEXT, true)
        root.addView(tvPace, lp(top = 2))
        tvExtra = tv("", 12f, MUTED)
        root.addView(tvExtra, lp(top = 2))

        btnStartNow = button("Nu starten", CARD, TEXT)
        btnStartNow.setOnClickListener { send("startnow") }
        root.addView(btnStartNow, lp(top = 10))

        btnPause = button("Pauze", ACCENT, ACCENT_INK)
        btnPause.setOnClickListener {
            val st = state?.optString("status")
            send(if (st == "PAUSED") "resume" else "pause")
        }
        root.addView(btnPause, lp(top = 10))

        btnStop = button("Stop", DANGER, Color.WHITE)
        btnStop.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - stopArmedAt < 3000) {
                stopArmedAt = 0
                send("stop")
            } else {
                stopArmedAt = now
            }
            render()
        }
        root.addView(btnStop, lp(top = 8))

        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
        send("sync")
        handler.post(ticker)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_STATE) return
        val j = try { JSONObject(String(event.data, Charsets.UTF_8)) } catch (e: Exception) { return }
        runOnUiThread {
            state = j
            lastMsgAt = SystemClock.elapsedRealtime()
            val km = j.optInt("k", 0)
            val st = j.optString("status")
            if (st == "RUNNING" && lastKm >= 0 && km > lastKm) buzz()
            lastKm = if (st == "RUNNING" || st == "PAUSED") km else -1
            render()
        }
    }

    private fun send(cmd: String) {
        val ctx = this
        Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) {
                tvStatus.text = "Telefoon niet verbonden"
            }
            nodes.forEach { n ->
                Wearable.getMessageClient(ctx).sendMessage(n.id, PATH_CMD, cmd.toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }

    private fun render() {
        val j = state
        val now = SystemClock.elapsedRealtime()
        if (j == null) {
            tvStatus.text = "Verbinden met telefoon…"
            showStats(false); showButtons(startNow = false, pause = false, stop = false)
            return
        }
        val st = j.optString("status")
        val stale = now - lastMsgAt > 12_000 && (st == "RUNNING" || st == "PAUSED" || st == "WAITING_GPS")
        val dist = j.optDouble("d", 0.0)
        var elapsed = j.optLong("t", 0L)
        if (st == "RUNNING" && !stale) elapsed += now - lastMsgAt
        val curPace = j.optDouble("p", -1.0)
        val avgPace = j.optDouble("a", -1.0)
        val goalPace = j.optDouble("g", -1.0)
        val steps = j.optInt("s", -1)

        when (st) {
            "IDLE" -> {
                tvStatus.text = "Start een training\nop je telefoon"
                showStats(false); showButtons(false, false, false)
            }
            "WAITING_GPS" -> {
                tvStatus.text = "GPS zoeken…"
                showStats(false); showButtons(startNow = true, pause = false, stop = true)
            }
            "RUNNING", "PAUSED" -> {
                tvStatus.text = if (st == "PAUSED") "Gepauzeerd" else "Bezig"
                showStats(true); showButtons(startNow = false, pause = true, stop = true)
                btnPause.text = if (st == "PAUSED") "Hervatten" else "Pauze"
            }
            "FINISHED" -> {
                tvStatus.text = "Training voltooid"
                showStats(true); showButtons(false, false, false)
            }
        }
        if (stale) tvStatus.text = "Geen verbinding\nmet telefoon"

        tvDistance.text = String.format(NL, "%.2f", dist / 1000.0)
        tvTime.text = time(elapsed)
        if (st == "FINISHED") {
            tvPace.text = "gem. ${pace(avgPace)} /km"
            tvPace.setTextColor(TEXT)
        } else {
            tvPace.text = "${pace(curPace)} /km"
            tvPace.setTextColor(
                when {
                    goalPace <= 0 || curPace <= 0 -> TEXT
                    abs(curPace - goalPace) <= 12 -> GOOD
                    else -> WARN
                }
            )
        }
        val parts = mutableListOf<String>()
        if (st != "FINISHED" && avgPace > 0) parts += "gem. ${pace(avgPace)}"
        if (goalPace > 0 && st != "FINISHED") parts += "doel ${pace(goalPace)}"
        if (steps >= 0) parts += "$steps stappen"
        tvExtra.text = parts.joinToString(" · ")

        val armed = now - stopArmedAt < 3000
        btnStop.text = if (armed) "Tik nog eens" else "Stop"
    }

    private fun showStats(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        tvDistance.visibility = v; tvUnit.visibility = v; tvTime.visibility = v
        tvPace.visibility = v; tvExtra.visibility = v
    }

    private fun showButtons(startNow: Boolean, pause: Boolean, stop: Boolean) {
        btnStartNow.visibility = if (startNow) View.VISIBLE else View.GONE
        btnPause.visibility = if (pause) View.VISIBLE else View.GONE
        btnStop.visibility = if (stop) View.VISIBLE else View.GONE
    }

    // ---------- formattering ----------

    private fun pace(sec: Double): String {
        if (sec <= 0 || sec.isNaN() || sec >= 3600) return "--:--"
        val t = sec.roundToInt()
        return String.format(NL, "%d:%02d", t / 60, t % 60)
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(NL, "%d:%02d:%02d", h, m, sec) else String.format(NL, "%02d:%02d", m, sec)
    }

    // ---------- view-hulpjes ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(text: String, bg: Int, fg: Int) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(22).toFloat()
            if (bg == CARD) setStroke(dp(1), LINE)
        }
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }
}
