package com.gmailorg.carradio

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ParkingCarActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var timerText: TextView
    private lateinit var status: TextView
    private val dataListener: () -> Unit = { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parking_car)
        list = findViewById(R.id.parkingList)
        input = findViewById(R.id.parkingInput)
        timerText = findViewById(R.id.parkingTimerText)
        status = findViewById(R.id.parkingStatus)

        findViewById<Button>(R.id.parkingAddButton).setOnClickListener {
            val address = input.text.toString().trim()
            if (address.isBlank()) return@setOnClickListener
            if (BluetoothListenerService.parkingAdd(address)) input.text.clear()
            else Toast.makeText(this, "Geen verbinding met telefoon", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.parkingPickTimeButton).setOnClickListener { pickTime() }
        findViewById<Button>(R.id.parkingClearTimeButton).setOnClickListener { BluetoothListenerService.parkingClearTimer() }
        findViewById<Button>(R.id.parkingRefreshButton).setOnClickListener { BluetoothListenerService.requestParking() }

        MessageBus.addDataListener(dataListener)
        BluetoothListenerService.requestParking()
        rebuild()
    }

    private fun pickTime() {
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m ->
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
            }
            BluetoothListenerService.parkingSetTimer(target.timeInMillis)
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
    }

    private fun rebuild() {
        if (isFinishing || isDestroyed) return
        status.text = if (BluetoothListenerService.isLive()) "Gesynchroniseerd met je telefoon" else "Wachten op telefoonverbinding"
        val timer = ParkingMirrorStore.timer(this)
        timerText.text = if (timer == null) "Geen eindtijd ingesteld" else "Melding om ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timer))}"
        list.removeAllViews()
        ParkingMirrorStore.items(this).forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 4.dp, 0, 4.dp) }
            val text = TextView(this).apply {
                this.text = item.address; setTextColor(ContextCompat.getColor(context, R.color.text_main)); textSize = 17f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = Button(this).apply { this.text = "Verwijder"; setOnClickListener { BluetoothListenerService.parkingRemove(item.id) } }
            row.addView(text); row.addView(del); list.addView(row)
        }
    }

    override fun onDestroy() { MessageBus.removeDataListener(dataListener); super.onDestroy() }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
