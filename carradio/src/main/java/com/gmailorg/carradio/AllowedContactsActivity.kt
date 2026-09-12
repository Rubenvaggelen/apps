package com.gmailorg.carradio

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class AllowedContactsActivity : AppCompatActivity() {
    private lateinit var filterSwitch: Switch
    private lateinit var container: LinearLayout
    private lateinit var manualInput: EditText
    private var rendering = false

    private val dataListener: () -> Unit = { renderContacts() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        filterSwitch = findViewById(R.id.filterSwitch)
        container = findViewById(R.id.contactContainer)
        manualInput = findViewById(R.id.manualContactInput)

        filterSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            if (!BluetoothListenerService.setContactFilterEnabled(checked)) {
                Toast.makeText(this, "Verbind eerst je telefoon om het filter te wijzigen", Toast.LENGTH_SHORT).show()
                rendering = true
                filterSwitch.isChecked = RadioContactStore.filterEnabled(this)
                rendering = false
                return@setOnCheckedChangeListener
            }
            RadioContactStore.setFilterEnabled(this, checked)
        }
        findViewById<Button>(R.id.refreshContactsButton).setOnClickListener {
            if (!BluetoothListenerService.requestContacts()) Toast.makeText(this, "Telefoon nog niet verbonden", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.addContactButton).setOnClickListener { addManualContact() }

        MessageBus.addDataListener(dataListener)
        BluetoothListenerService.requestContacts()
    }

    private fun addManualContact() {
        val name = manualInput.text.toString().trim()
        if (name.isBlank()) return
        if (!BluetoothListenerService.isLive()) {
            Toast.makeText(this, "Verbind eerst je telefoon", Toast.LENGTH_SHORT).show()
            return
        }
        BluetoothListenerService.setContactFilterEnabled(true)
        val sent = BluetoothListenerService.setContactAllowed(name, true)
        if (!sent) {
            Toast.makeText(this, "Contact kon niet naar de telefoon worden gestuurd", Toast.LENGTH_SHORT).show()
            return
        }
        RadioContactStore.setFilterEnabled(this, true)
        RadioContactStore.setAllowedLocal(this, name, true)
        manualInput.text.clear()
        renderContacts()
    }

    private fun renderContacts() {
        if (isFinishing || isDestroyed) return
        rendering = true
        filterSwitch.isChecked = RadioContactStore.filterEnabled(this)
        rendering = false
        container.removeAllViews()

        val allowed = RadioContactStore.allowed(this)
        val contacts = (RadioContactStore.known(this) + allowed)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase(Locale.ROOT) }

        if (contacts.isEmpty()) {
            val cb = CheckBox(this).apply {
                text = "Nog geen WhatsApp-contacten gezien. Voeg hierboven een naam toe of druk op vernieuwen."
                isEnabled = false
                setTextColor(ContextCompat.getColor(this@AllowedContactsActivity, R.color.text_dim))
            }
            container.addView(cb)
            return
        }

        contacts.forEach { name ->
            val box = CheckBox(this).apply {
                text = name
                textSize = 17f
                setTextColor(ContextCompat.getColor(this@AllowedContactsActivity, R.color.text_main))
                isChecked = allowed.any { it.equals(name, ignoreCase = true) }
                setPadding(8, 8, 8, 8)
                setOnCheckedChangeListener { _, checked ->
                    if (!BluetoothListenerService.setContactAllowed(name, checked)) {
                        Toast.makeText(this@AllowedContactsActivity, "Telefoon nog niet verbonden", Toast.LENGTH_SHORT).show()
                        container.post { renderContacts() }
                        return@setOnCheckedChangeListener
                    }
                    RadioContactStore.setAllowedLocal(this@AllowedContactsActivity, name, checked)
                }
            }
            container.addView(box)
        }
    }

    override fun onResume() {
        super.onResume()
        renderContacts()
        BluetoothListenerService.requestContacts()
    }

    override fun onDestroy() {
        MessageBus.removeDataListener(dataListener)
        super.onDestroy()
    }
}
