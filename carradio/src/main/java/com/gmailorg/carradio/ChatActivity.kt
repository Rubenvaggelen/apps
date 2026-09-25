package com.gmailorg.carradio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    @Volatile private var typedSendInProgress = false
    private var chatVoiceDucked = false
    private var recordingDucked = false
    private val duckHandler = Handler(Looper.getMainLooper())
    private val releaseRequestDuck = Runnable {
        UsbPlaybackService.endDucking(UsbPlaybackService.DUCK_REASON_CHAT_REQUEST)
    }

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
        MenuButtonHelper.attach(this)

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
        if (text.isBlank() || typedSendInProgress) return

        // Probeer eerst direct. Als de Wi-Fi/hotspot-link net opnieuw aan het
        // verbinden is, mag een getypt bericht niet meteen verloren gaan.
        if (BluetoothListenerService.sendTextReply(contact, text)) {
            input.text.clear()
            status.text = "Bericht verzonden naar telefoon…"
            return
        }

        typedSendInProgress = true
        status.text = "Telefoonverbinding herstellen • bericht blijft klaarstaan…"

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BluetoothListenerService::class.java)
            )
        } catch (_: Exception) {
        }

        Thread {
            var sent = false
            for (attempt in 0 until 12) {
                if (attempt > 0) {
                    try { Thread.sleep(900L) } catch (_: InterruptedException) {}
                }
                if (BluetoothListenerService.sendTextReply(contact, text)) {
                    sent = true
                    break
                }
            }

            runOnUiThread {
                typedSendInProgress = false
                if (sent) {
                    // Alleen wissen als de gebruiker intussen niet alweer verder typte.
                    if (input.text.toString().trim() == text) input.text.clear()
                    status.text = "Bericht verzonden naar telefoon…"
                } else {
                    status.text = "Geen live verbinding met je telefoon"
                    Toast.makeText(this, "Geen live verbinding met je telefoon", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
        status.text = "🎙️ Spreek je antwoord in • automatisch verwerken na 5 sec stilte"
        voiceButton.text = "⏹"
        recordingDucked = true
        UsbPlaybackService.beginDucking(UsbPlaybackService.DUCK_REASON_RECORDING, 0.12f)
        voiceRecorder.start(onComplete = { result ->
            if (recordingDucked) {
                recordingDucked = false
                UsbPlaybackService.endDucking(UsbPlaybackService.DUCK_REASON_RECORDING)
            }
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
            val isIncomingImage = !msg.mine && (
                msg.mediaMime?.startsWith("image/", true) == true || looksImageMessage(msg.text)
            )
            val bubble = TextView(this).apply {
                text = when {
                    msg.voiceNote && !msg.mine -> "🎤 ${msg.text}  • tik om af te spelen"
                    isIncomingImage -> "🖼️ ${msg.text}  • tik om te openen"
                    else -> msg.text
                }
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 17f
                setBackgroundResource(if (msg.mine) R.drawable.bg_chat_out else R.drawable.bg_chat_in)
                maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()

                if (msg.voiceNote && !msg.mine) {
                    setOnClickListener {
                        val path = msg.mediaPath
                        if (!path.isNullOrBlank()) {
                            playReceivedVoice(path)
                        } else {
                            status.text = "Spraakbericht ophalen van je telefoon…"
                            duckHandler.removeCallbacks(releaseRequestDuck)
                            UsbPlaybackService.beginDucking(UsbPlaybackService.DUCK_REASON_CHAT_REQUEST, 0.04f)
                            val holdMs = voiceRequestDuckDurationMs(msg.text)
                            duckHandler.postDelayed(releaseRequestDuck, holdMs)
                            if (!BluetoothListenerService.requestVoiceNote(contact)) {
                                duckHandler.removeCallbacks(releaseRequestDuck)
                                UsbPlaybackService.endDucking(UsbPlaybackService.DUCK_REASON_CHAT_REQUEST)
                                Toast.makeText(this@ChatActivity, "Geen live verbinding met je telefoon", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else if (isIncomingImage) {
                    setOnClickListener {
                        val path = msg.mediaPath
                        if (!path.isNullOrBlank()) {
                            openImage(path)
                        } else {
                            status.text = "Afbeelding ophalen van je telefoon…"
                            if (!BluetoothListenerService.requestMedia(contact, "image")) {
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
        releaseChatVoicePlayer()
        try {
            chatVoiceDucked = true
            UsbPlaybackService.beginDucking(UsbPlaybackService.DUCK_REASON_CHAT_PLAYBACK, 0.03f)
            receivedVoicePlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(path)
                setOnPreparedListener { it.start(); status.text = "▶ Spraakbericht wordt afgespeeld" }
                setOnCompletionListener {
                    status.text = "✅ Spraakbericht afgespeeld"
                    releaseChatVoicePlayer()
                }
                setOnErrorListener { _, _, _ ->
                    status.text = "Spraakbericht kon niet worden afgespeeld"
                    releaseChatVoicePlayer()
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            status.text = "Spraakbericht kon niet worden afgespeeld"
            releaseChatVoicePlayer()
        }
    }

    private fun releaseChatVoicePlayer() {
        try { receivedVoicePlayer?.release() } catch (_: Exception) {}
        receivedVoicePlayer = null
        if (chatVoiceDucked) {
            chatVoiceDucked = false
            UsbPlaybackService.endDucking(UsbPlaybackService.DUCK_REASON_CHAT_PLAYBACK)
        }
    }

    private fun looksImageMessage(text: String): Boolean {
        val value = text.lowercase(Locale.ROOT)
        return value.contains("foto") || value.contains("photo") || value.contains("afbeelding") ||
            value.contains("image") || value.startsWith("🖼") || value.startsWith("📷")
    }

    private fun openImage(path: String) {
        startActivity(
            Intent(this, ImageViewerActivity::class.java)
                .putExtra(ImageViewerActivity.EXTRA_PATH, path)
                .putExtra(ImageViewerActivity.EXTRA_TITLE, ContactAliases.displayName(contact))
        )
    }

    private fun voiceRequestDuckDurationMs(text: String): Long {
        val match = Regex("""(\d{1,2}):(\d{2})""").find(text)
        val seconds = if (match != null) {
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val secs = match.groupValues[2].toLongOrNull() ?: 0L
            minutes * 60L + secs
        } else 20L
        return ((seconds + 8L) * 1000L).coerceIn(12_000L, 120_000L)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        refreshMessages()
    }

    override fun onDestroy() {
        voiceRecorder.stop()
        if (recordingDucked) {
            recordingDucked = false
            UsbPlaybackService.endDucking(UsbPlaybackService.DUCK_REASON_RECORDING)
        }
        duckHandler.removeCallbacks(releaseRequestDuck)
        UsbPlaybackService.endDucking(UsbPlaybackService.DUCK_REASON_CHAT_REQUEST)
        releaseChatVoicePlayer()
        MessageBus.removeDataListener(dataListener)
        MessageBus.removeStatusListener(statusListener)
        super.onDestroy()
    }
}
