package com.gmailorg.carradio

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WhatsAppConversationsActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var connectionText: TextView

    private val dataListener: () -> Unit = { refresh() }
    private val statusListener: (String) -> Unit = { connectionText.text = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversations)
        container = findViewById(R.id.conversationContainer)
        emptyText = findViewById(R.id.emptyText)
        connectionText = findViewById(R.id.connectionText)

        findViewById<View>(R.id.manageContactsButton).setOnClickListener {
            startActivity(Intent(this, AllowedContactsActivity::class.java))
        }
        MessageBus.addDataListener(dataListener)
        MessageBus.addStatusListener(statusListener)
        BluetoothListenerService.requestContacts()
    }

    private fun refresh() {
        if (isFinishing || isDestroyed) return
        val summaries = ConversationStore.summaries(this)
        container.removeAllViews()
        emptyText.visibility = if (summaries.isEmpty()) View.VISIBLE else View.GONE

        summaries.forEach { summary ->
            val row = LayoutInflater.from(this).inflate(R.layout.view_conversation_row, container, false)
            row.findViewById<TextView>(R.id.contactName).text = summary.contact
            row.findViewById<TextView>(R.id.latestText).text = summary.latestText
            row.findViewById<TextView>(R.id.timeText).text = formatTime(summary.latestTime)
            row.findViewById<TextView>(R.id.unreadText).text = if (summary.unread > 0) "● ${summary.unread}" else ""
            row.setOnClickListener {
                startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CONTACT, summary.contact))
            }
            container.addView(row)
        }
    }

    private fun formatTime(time: Long): String {
        if (time <= 0) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
    }

    override fun onResume() {
        super.onResume()
        refresh()
        BluetoothListenerService.requestContacts()
    }

    override fun onDestroy() {
        MessageBus.removeDataListener(dataListener)
        MessageBus.removeStatusListener(statusListener)
        super.onDestroy()
    }
}
