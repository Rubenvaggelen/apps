package com.gmailorg.carradio

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.security.MessageDigest

class AllowedContactsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private var unlocked = false
    private var rendering = false

    private val dataListener: () -> Unit = {
        if (unlocked) renderContacts()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        MenuButtonHelper.attach(this)
        container = findViewById(R.id.contactContainer)

        RadioContactStore.resetForReconnect(this)

        findViewById<Button>(R.id.refreshContactsButton).setOnClickListener {
            if (!BluetoothListenerService.requestContacts()) {
                Toast.makeText(this, "Telefoon nog niet verbonden", Toast.LENGTH_SHORT).show()
            }
        }

        MessageBus.addDataListener(dataListener)
        showPinDialog()
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            hint = "Pincode"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
            setPadding(32, 16, 32, 16)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Vaste contacten beveiligd")
            .setMessage("Voer de pincode in om de vaste WhatsApp-contacten aan of uit te zetten.")
            .setView(input)
            .setPositiveButton("Ontgrendelen", null)
            .setNegativeButton("Terug naar menu") { _, _ -> goToMenu() }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (isCorrectPin(input.text?.toString().orEmpty())) {
                    unlocked = true
                    dialog.dismiss()
                    renderContacts()
                    BluetoothListenerService.requestContacts()
                } else {
                    input.text?.clear()
                    input.error = "Onjuiste pincode"
                }
            }
        }
        dialog.show()
    }

    private fun isCorrectPin(value: String): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(value.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return hash == "616f55173c48091a11f9d643846e32f77f9f949896747c85cf953d931956c8fe"
    }

    private fun renderContacts() {
        if (!unlocked || isFinishing || isDestroyed) return
        container.removeAllViews()
        val allowed = RadioContactStore.allowed(this)

        ContactAliases.fixedContacts.forEach { contact ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 10, 10, 10)
            }

            val toggle = Switch(this).apply {
                text = contact.displayName
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@AllowedContactsActivity, R.color.text_main))
                isChecked = allowed.any { it.equals(contact.realName, ignoreCase = true) }
                setPadding(8, 6, 8, 6)
                setOnCheckedChangeListener { _, checked ->
                    if (rendering) return@setOnCheckedChangeListener
                    if (!BluetoothListenerService.setContactAllowed(contact.realName, checked)) {
                        Toast.makeText(
                            this@AllowedContactsActivity,
                            "Telefoon nog niet verbonden. Verbind eerst om de keuze te wijzigen.",
                            Toast.LENGTH_SHORT
                        ).show()
                        container.post { renderContacts() }
                        return@setOnCheckedChangeListener
                    }
                    RadioContactStore.setAllowedLocal(this@AllowedContactsActivity, contact.realName, checked)
                }
            }
            row.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            contact.phoneNumber?.let { number ->
                val numberText = TextView(this).apply {
                    text = number
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@AllowedContactsActivity, R.color.text_dim))
                    setPadding(16, 0, 8, 6)
                }
                row.addView(numberText)
            }

            container.addView(row)
        }
    }

    private fun goToMenu() {
        MenuButtonHelper.goToMenu(this)
    }

    override fun onResume() {
        super.onResume()
        if (unlocked) {
            renderContacts()
            BluetoothListenerService.requestContacts()
        }
    }

    override fun onDestroy() {
        MessageBus.removeDataListener(dataListener)
        super.onDestroy()
    }
}
