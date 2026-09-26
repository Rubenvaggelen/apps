package com.theone.remote

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("the_one_remote", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestedId = intent.getStringExtra("surface_id").orEmpty().trim()
        if (requestedId.isNotBlank()) {
            prefs.edit().putString("surface_id", requestedId).apply()
            if (intent.getBooleanExtra("auto_connect", false)) {
                startActivity(
                    Intent(this, RemoteActivity::class.java)
                        .putExtra("surface_id", requestedId)
                )
                finish()
                return
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.parseColor("#071018"))
        }

        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 0.35f))

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.the_one_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "The One"
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(180), dp(180)))

        val title = TextView(this).apply {
            text = "The One Remote"
            textSize = 28f
            setTextColor(Color.parseColor("#F3F8FC"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val subtitle = TextView(this).apply {
            text = "Bereik je Surface vanaf je telefoon"
            textSize = 15f
            setTextColor(Color.parseColor("#9FB2C2"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(26))
        }
        root.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val idField = EditText(this).apply {
            hint = "Surface-ID"
            setHintTextColor(Color.parseColor("#6F8496"))
            setTextColor(Color.WHITE)
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(requestedId.ifBlank { prefs.getString("surface_id", "461504832") ?: "461504832" })
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#0F1C29"))
        }
        root.addView(idField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply {
            bottomMargin = dp(14)
        })

        val connect = Button(this).apply {
            text = "Verbinden met Surface"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.parseColor("#00131D"))
            setBackgroundColor(Color.parseColor("#20B7FF"))
            setOnClickListener {
                val id = idField.text.toString().trim()
                if (id.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Vul eerst je Surface-ID in.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("surface_id", id).apply()
                startActivity(Intent(this@MainActivity, RemoteActivity::class.java).putExtra("surface_id", id))
            }
        }
        root.addView(connect, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))

        val help = TextView(this).apply {
            text = "Eenmalige instelling: op je Surface moet de remote-host actief zijn. Je wachtwoord wordt niet in deze app opgeslagen."
            textSize = 12f
            setTextColor(Color.parseColor("#7890A3"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), 0)
        }
        root.addView(help, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 0.65f))
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
