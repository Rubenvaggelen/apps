package com.gmailorg.carradio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {
    companion object { const val EXTRA_CONTACT = "contact" }

    private lateinit var contact: String
    private lateinit var messageList: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var voiceButton: Button
    private val voiceRecorder = RadioVoiceRecorder()
    private var receivedVoicePlayer: MediaPlayer? = null

    private val dataListener: () -> Unit = { refreshMessages() }
    private val statusListener: (String) -> Unit = { status.text = it }
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecording() else Toast.makeText(this, "Microfoontoegang is nodig", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contact = intent.getStringExtra(EXTRA_CONTACT).orEmpty()
        if (contact.isBlank()) { finish(); return }
        setContentView(R.layout.activity_chat)

        findViewById<TextView>(R.id.chatTitle).text = ContactAliases.displayName(contact)
        messageList = findViewById(R.id.messageList)
        scroll = findViewById(R.id.chatScroll)
        status = findViewById(R.id.chatStatus)
        input = findViewById(R.id.replyInput)
        voiceButton = findViewById(R.id.voiceButton)

        findViewById<Button>(R.id.sendButton).setOnClickListener { sendTyped() }
        voiceButton.setOnClickListener {
            if (voiceRecorder.isRecording()) {
                voiceRecorder.stop()
                voiceButton.text = "🎤"
            } else ensureMicThenRecord()
        }

        ConversationStore.markRead(this, contact)
        MessageBus.addDataListener(dataListener)
        MessageBus.addStatusListener(statusListener)
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isBlank()) return
        val ok = BluetoothListenerService.sendTextReply(contact, text)
        if (ok) input.text.clear()
        else Toast.makeText(this, "Geen live verbinding met je telefoon", Toast.LENGTH_SHORT).show()
    }

    private fun ensureMicThenRecord() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startVoiceRecording() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceRecording() {
        if (!BluetoothListenerService.isLive()) {
            Toast.makeText(this, "Wacht tot je telefoon verbonden is", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "🎙️ Spreek je antwoord in • auto-verzenden na 5 sec stilte • max 90 sec"
        voiceButton.text = "⏹"
        voiceRecorder.start(onComplete = { result ->
            runOnUiThread { voiceButton.text = "🎤" }
            when (result) {
                is RadioVoiceRecorder.Result.Success -> {
                    val sent = BluetoothListenerService.sendVoiceAudio(contact, result.wavBytes)
                    runOnUiThread {
                        status.text = if (sent) "Audio verzonden • antwoord wordt verwerkt..." else "Verbinding viel weg"
                    }
                }
                is RadioVoiceRecorder.Result.Error -> runOnUiThread {
                    status.text = "Opname mislukt: ${result.message}"
                }
            }
        })
    }

    private fun refreshMessages() {
        if (isFinishing || isDestroyed) return
        ConversationStore.markRead(this, contact)
        val messages = ConversationStore.messagesFor(this, contact)
        messageList.removeAllViews()
        messages.forEach { msg ->
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (msg.mine) Gravity.END else Gravity.START
                setPadding(0, 4.dp, 0, 4.dp)
            }
            val bubble = TextView(this).apply {
                text = if (msg.voiceNote && !msg.mine) "🎤 ${msg.text}  • tik om af te spelen" else msg.text
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 17f
                setBackgroundResource(if (msg.mine) R.drawable.bg_chat_out else R.drawable.bg_chat_in)
                maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
                if (msg.voiceNote && !msg.mine) {
                    setOnClickListener {
                        val path = msg.mediaPath
                        if (!path.isNullOrBlank()) playReceivedVoice(path)
                        else {
                            status.text = "Spraakbericht ophalen van je telefoon…"
                            if (!BluetoothListenerService.requestVoiceNote(contact)) {
                                Toast.makeText(this@ChatActivity, "Geen live verbinding met je telefoon", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            val time = TextView(this).apply {
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.time))
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                textSize = 11f
                gravity = if (msg.mine) Gravity.END else Gravity.START
            }
            wrapper.addView(bubble)
            wrapper.addView(time)
            messageList.addView(wrapper)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun playReceivedVoice(path: String) {
        try { receivedVoicePlayer?.release() } catch (_: Exception) {}
        try {
            receivedVoicePlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(path)
                setOnPreparedListener { it.start(); status.text = "▶ Spraakbericht wordt afgespeeld" }
                setOnCompletionListener { status.text = "✅ Spraakbericht afgespeeld" }
                prepareAsync()
            }
        } catch (_: Exception) {
            status.text = "Spraakbericht kon niet worden afgespeeld"
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        refreshMessages()
    }

    override fun onDestroy() {
        voiceRecorder.stop()
        try { receivedVoicePlayer?.release() } catch (_: Exception) {}
        MessageBus.removeDataListener(dataListener)
        MessageBus.removeStatusListener(statusListener)
        super.onDestroy()
    }
}
