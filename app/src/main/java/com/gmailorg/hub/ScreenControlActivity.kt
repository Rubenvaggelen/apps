package com.gmailorg.hub

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Owner-only screen power controls. These NEVER sleep or shut down Windows. */
class ScreenControlActivity : AppCompatActivity() {
    private data class Target(val id: String, val title: String, val host: String, val screens: Int)
    private val targets = listOf(
        Target("ruben", "Ruben", "192.168.178.193", 3),
        Target("surface", "Surface", "192.168.178.154", 1),
        Target("hub", "The One Hub", "192.168.178.183", 1)
    )
    private lateinit var body: LinearLayout
    private lateinit var info: TextView
    private var permitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ScreenControlActivity, R.color.bg))
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            text = "←"
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Schermen"
            textSize = 23f
            setTextColor(ContextCompat.getColor(this@ScreenControlActivity, R.color.text_main))
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(header)
        info = TextView(this).apply {
            text = "Eigenaar en verbindingen controleren…"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ScreenControlActivity, R.color.text_dim))
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(info)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        MenuButtonHelper.attach(this)

        Thread {
            val authenticated = runCatching {
                MainDeviceRegistry.heartbeat(this)
                MainDeviceRegistry.isLocallyOwner(this) && !MainDeviceRegistry.isLocallyBlocked(this)
            }.getOrDefault(false)
            runOnUiThread {
                permitted = authenticated
                info.text = if (authenticated) {
                    "Alleen schermen aan/uit. Je computers, DJ en Shared Media blijven draaien. Werkt via je thuisnetwerk."
                } else "Geen geverifieerde eigenaarstoegang. Schermbediening is geblokkeerd."
                if (authenticated) targets.forEach(::addTarget)
            }
        }.start()
    }

    private fun addTarget(target: Target) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            setBackgroundColor(ContextCompat.getColor(this@ScreenControlActivity, R.color.surface))
        }
        panel.addView(TextView(this).apply {
            text = "${target.title} • ${target.screens} scherm${if (target.screens == 1) "" else "en"}"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ScreenControlActivity, R.color.amber))
        })
        val state = TextView(this).apply {
            text = "Tik op Aan of Uit."
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ScreenControlActivity, R.color.text_main))
        }
        panel.addView(state)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val on = Button(this).apply {
            text = "Scherm AAN"
            setOnClickListener { operate(target, "ON", state, controls) }
        }
        val off = Button(this).apply {
            text = "Scherm UIT"
            setOnClickListener { operate(target, "OFF", state, controls) }
        }
        controls.addView(on, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(off, LinearLayout.LayoutParams(0, dp(52), 1f))
        panel.addView(controls)
        body.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun operate(target: Target, action: String, state: TextView, controls: LinearLayout) {
        if (!permitted) return
        for (i in 0 until controls.childCount) controls.getChildAt(i).isEnabled = false
        state.text = "Beveiligde opdracht naar ${target.title} verzenden…"
        Thread {
            // Main only authenticates over HTTPS; Windows polls outbound so no open firewall port.
            val result = runCatching {
                val ticket = MainLicenseClient.screenTicket(this, target.id, action)
                var latest = "pending"
                for (attempt in 1..15) {
                    Thread.sleep(1100)
                    latest = runCatching {
                        MainLicenseClient.screenCommandStatus(this, target.id, ticket)
                    }.getOrDefault("pending")
                    if (latest == "done" || latest == "failed") break
                }
                latest
            }
            runOnUiThread {
                for (i in 0 until controls.childCount) controls.getChildAt(i).isEnabled = true
                state.text = when (result.getOrNull()) {
                    "done" -> "${if (action == "ON") "Aan" else "Uit"}-commando uitgevoerd door Windows. Fysieke schermstatus niet uitleesbaar."
                    "failed" -> "Windows heeft de opdracht ontvangen, maar uitvoeren is mislukt."
                    "pending", "executing" -> "Opdracht wacht nog op Windows. Controleer of ${target.title} actief is."
                    else -> "Opdracht mislukt: ${result.exceptionOrNull()?.message ?: "server niet bereikbaar"}"
                }
            }
        }.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
