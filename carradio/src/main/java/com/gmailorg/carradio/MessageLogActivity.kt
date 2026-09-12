package com.gmailorg.carradio

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Meldingen in The One Car zijn bewust alleen inkomende WhatsApp-berichten.
 * Technische status, transcriptie en verzendlogs horen niet in dit scherm.
 */
class MessageLogActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private val dataListener: () -> Unit = { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_log)
        container = findViewById(R.id.logContainer)
        status = findViewById(R.id.logStatus)
        status.visibility = View.GONE
        MessageBus.addDataListener(dataListener)
        rebuild()
    }

    private fun rebuild() {
        if (isFinishing || isDestroyed) return
        container.removeAllViews()
        val messages = ConversationStore.recentIncoming(this, 80)

        if (messages.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Nog geen WhatsApp-berichten ontvangen."
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                textSize = 16f
                setPadding(0, 12, 0, 12)
            })
            return
        }

        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        messages.forEach { msg ->
            val view = TextView(this).apply {
                val time = formatter.format(Date(msg.time))
                text = "$time  •  ${ContactAliases.displayName(msg.contact)}: ${msg.text}"
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 17f
                setPadding(0, 10, 0, 10)
            }
            container.addView(view)
        }
    }

    override fun onDestroy() {
        MessageBus.removeDataListener(dataListener)
        super.onDestroy()
    }
}
