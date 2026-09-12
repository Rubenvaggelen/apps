package com.gmailorg.carradio

import android.os.Bundle
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class HouseholdCarActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var input: EditText
    private lateinit var alertSwitch: Switch
    private lateinit var status: TextView
    private var suppressSwitch = false
    private val dataListener: () -> Unit = { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_household_car)
        list = findViewById(R.id.householdList)
        empty = findViewById(R.id.householdEmpty)
        input = findViewById(R.id.householdInput)
        alertSwitch = findViewById(R.id.householdAlertSwitch)
        status = findViewById(R.id.householdStatus)

        findViewById<Button>(R.id.householdAddButton).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener
            if (BluetoothListenerService.householdAdd(text)) input.text.clear()
            else Toast.makeText(this, "Geen verbinding met telefoon", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.householdClearDoneButton).setOnClickListener {
            BluetoothListenerService.householdClearDone()
        }
        findViewById<Button>(R.id.householdRefreshButton).setOnClickListener {
            BluetoothListenerService.requestHousehold()
        }

        alertSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (!BluetoothListenerService.setHouseholdAlert(checked)) {
                Toast.makeText(this, "Geen verbinding met telefoon", Toast.LENGTH_SHORT).show()
                rebuild()
            }
        }

        MessageBus.addDataListener(dataListener)
        BluetoothListenerService.requestHousehold()
        rebuild()
    }

    private fun rebuild() {
        if (isFinishing || isDestroyed) return
        val items = HouseholdMirrorStore.items(this)
        list.removeAllViews()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        suppressSwitch = true
        alertSwitch.isChecked = HouseholdMirrorStore.alertEnabled(this)
        suppressSwitch = false
        status.text = if (BluetoothListenerService.isLive()) "Gesynchroniseerd met je telefoon" else "Wachten op telefoonverbinding"

        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dp, 5.dp, 8.dp, 5.dp)
            }
            val check = CheckBox(this).apply {
                isChecked = item.done
                setOnCheckedChangeListener { _, _ -> BluetoothListenerService.householdToggle(item.id) }
            }
            val text = TextView(this).apply {
                this.text = item.text
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (item.done) {
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    alpha = .55f
                }
            }
            val del = Button(this).apply {
                text = "Verwijder"
                setOnClickListener { BluetoothListenerService.householdRemove(item.id) }
            }
            row.addView(check)
            row.addView(text)
            row.addView(del)
            list.addView(row)
        }
    }

    override fun onDestroy() {
        MessageBus.removeDataListener(dataListener)
        super.onDestroy()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
