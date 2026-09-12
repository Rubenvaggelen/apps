package com.gmailorg.carradio

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageLogActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private val messageListener: (String) -> Unit = { rebuild() }
    private val statusListener: (String) -> Unit = { text -> status.text = text }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_log)
        container = findViewById(R.id.logContainer)
        status = findViewById(R.id.logStatus)
        MessageBus.addListener(messageListener)
        MessageBus.addStatusListener(statusListener)
        rebuild()
    }

    private fun rebuild() {
        if (isFinishing || isDestroyed) return
        container.removeAllViews()
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        MessageBus.history().asReversed().forEach { line ->
            val view = TextView(this).apply {
                text = "$now  •  $line"
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 16f
                setPadding(0, 8, 0, 8)
            }
            container.addView(view)
        }
    }

    override fun onDestroy() {
        MessageBus.removeListener(messageListener)
        MessageBus.removeStatusListener(statusListener)
        super.onDestroy()
    }
}
