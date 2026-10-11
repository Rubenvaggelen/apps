package com.gmailorg.hub

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Owner-only screen power controls. These NEVER sleep or shut down Windows. */
class ScreenControlActivity : AppCompatActivity() {
    private data class Target(val id: String, val title: String, val host: String, val screens: Int)
    private val targets = listOf(
        Target("ruben", "Ruben", "192.168.178.193", 3),
        Target("surface", "Surface", "192.168.178.154", 1),
        Target("hub", "The One Hub", "192.168.178.183", 1)
    )
    private val prefs by lazy { getSharedPreferences("screen_control_paired", MODE_PRIVATE) }
    private val keyAlias = "the_one_screen_control_pin_v1"
    private val port = 38492
    private lateinit var body: LinearLayout
    private lateinit var info: TextView
    private var permitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MenuButtonHelper.attach(this)
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
        val saved = recoverPin(target.id)
        if (saved == null) {
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Bestaande apparaatbedieningscode"
                isSingleLine = true
            }
            AlertDialog.Builder(this)
                .setTitle("Eenmalig ${target.title} koppelen")
                .setMessage("Vul de bestaande bedieningscode voor ${target.title} in. Na succesvolle koppeling volstaat één druk op Aan of Uit.")
                .setView(edit)
                .setNegativeButton("Annuleren", null)
                .setPositiveButton("Koppelen en uitvoeren") { _, _ ->
                    val pin = edit.text.toString()
                    if (pin.isNotBlank() && pin.length <= 60) send(target, action, pin, state, controls, true)
                }.show()
        } else send(target, action, saved, state, controls, false)
    }

    private fun send(target: Target, action: String, pin: String, state: TextView, controls: LinearLayout, firstPair: Boolean) {
        controls.isEnabled = false
        for (i in 0 until controls.childCount) controls.getChildAt(i).isEnabled = false
        state.text = "Verbinding met ${target.title}…"
        Thread {
            val response = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.host, port), 2800)
                    socket.soTimeout = 3800
                    socket.getOutputStream().write("$action $pin\n".toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine().orEmpty()
                }
            }
            runOnUiThread {
                controls.isEnabled = true
                for (i in 0 until controls.childCount) controls.getChildAt(i).isEnabled = true
                val value = response.getOrNull().orEmpty()
                when {
                    value == "OK DISPLAY_$action" -> {
                        if (firstPair) runCatching { storePin(target.id, pin) }
                        state.text = "${if (action == "ON") "Aan" else "Uit"}-commando geaccepteerd; fysieke schermstatus niet uitleesbaar."
                    }
                    value == "ERR AUTH" -> {
                        prefs.edit().remove(target.id).apply()
                        state.text = "Code geweigerd. Tik opnieuw om opnieuw te koppelen."
                    }
                    value == "ERR LOCKED" -> state.text = "Tijdelijk vergrendeld na foutieve codes."
                    else -> state.text = "Niet bereikbaar of opdracht mislukt. Controleer de thuisverbinding en of Windows draait."
                }
            }
        }.start()
    }

    private fun secureKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }

    private fun storePin(device: String, pin: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secureKey())
        val bytes = cipher.iv + cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(device, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    private fun recoverPin(device: String): String? = runCatching {
        val enc = prefs.getString(device, null) ?: return@runCatching null
        val bytes = Base64.decode(enc, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secureKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
