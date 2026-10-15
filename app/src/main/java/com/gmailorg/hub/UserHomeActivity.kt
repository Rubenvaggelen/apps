package com.gmailorg.hub

import android.content.Intent
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
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        active = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun root(): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
        val brand = TextView(this).apply {
            text = "THE ONE"
            textSize = 35f
            setTextColor(blue)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        content.addView(brand)
        content.addView(TextView(this).apply {
            text = "Part of The One Family"
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(dim)
            setPadding(0, 0, 0, dp(25))
        })
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

    private fun showMenu() {
        val parent = root()
        label(parent, "Welkom, ${MainDeviceRegistry.personName(this)}", 20f)
        label(parent, "Je toegang is goedgekeurd. Mediarechten worden per onderdeel ingesteld.", 14f)
        button(parent, "Muziek en films") {
            openFeature(MainDeviceRegistry.ACCESS_MEDIA_PLAYER, "Media Player", MoviesActivity::class.java)
        }
        button(parent, "The One Mixes") {
            openFeature(MainDeviceRegistry.ACCESS_MIXES, "Mixes", SupremacyMixesActivity::class.java)
        }
        button(parent, "Shared Media en Favorites") {
            openFeature(MainDeviceRegistry.ACCESS_SHARED, "Shared Media", MoviesActivity::class.java)
        }
        button(parent, "Vraag het") { startActivity(Intent(this, AskActivity::class.java)) }
        button(parent, "Recepten") { startActivity(Intent(this, RecipesActivity::class.java)) }
        button(parent, "Nieuws") { startActivity(Intent(this, NewsActivity::class.java)) }
        button(parent, "Radio") { startActivity(Intent(this, RadioActivity::class.java)) }
        button(parent, "Koers") { startActivity(Intent(this, CurrencyActivity::class.java)) }
        button(parent, "Route") { startActivity(Intent(this, RouteHubActivity::class.java)) }
        button(parent, "Controleer mijn rechten") { showChecking(); checkRegistration() }
    }
}
