package com.gmailorg.carradio

import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CarPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_PLAYER = "the_one_from_player"
        const val EXTRA_OPEN_MUSIC = "the_one_open_music"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var currentTitle: TextView
    private lateinit var currentTime: TextView
    private lateinit var nextTitle: TextView
    private lateinit var nextStatus: TextView
    private lateinit var currentWaveform: CarWaveformView
    private lateinit var nextWaveform: CarWaveformView
    private lateinit var playPauseA: TextView
    private lateinit var bottomPlayPause: TextView
    private lateinit var muteA: TextView
    private lateinit var muteB: TextView
    private lateinit var autoButton: TextView
    private lateinit var playlistPanel: LinearLayout
    private lateinit var playlistList: LinearLayout
    private lateinit var bottomTitle: TextView
    private lateinit var volumeSeek: SeekBar
    private lateinit var audioManager: AudioManager
    private var playlistSignature = ""

    private val refreshTick = object : Runnable {
        override fun run() {
            refreshPlayer()
            handler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_car_player)
        enterImmersive()

        UsbPlaybackService.resumeLastSessionIfNeeded(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        currentTitle = findViewById(R.id.playerCurrentTitle)
        currentTime = findViewById(R.id.playerCurrentTime)
        nextTitle = findViewById(R.id.playerNextTitle)
        nextStatus = findViewById(R.id.playerNextStatus)
        currentWaveform = findViewById(R.id.playerCurrentWaveform)
        nextWaveform = findViewById(R.id.playerNextWaveform)
        playPauseA = findViewById(R.id.playerPlayPauseA)
        bottomPlayPause = findViewById(R.id.playerBottomPlayPause)
        muteA = findViewById(R.id.playerMuteA)
        muteB = findViewById(R.id.playerMuteB)
        autoButton = findViewById(R.id.playerAutoButton)
        playlistPanel = findViewById(R.id.playerPlaylistPanel)
        playlistList = findViewById(R.id.playerPlaylistList)
        bottomTitle = findViewById(R.id.playerBottomTitle)
        volumeSeek = findViewById(R.id.playerVolumeSeek)

        currentWaveform.accentColor = Color.parseColor("#20B8FF")
        nextWaveform.accentColor = Color.parseColor("#E8AA4E")

        configureNavigation()
        configurePlaybackControls()
        configureVolume()

        handler.post(refreshTick)
    }

    private fun configureNavigation() {
        findViewById<TextView>(R.id.playerMenuButton).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
            )
            finish()
        }

        findViewById<TextView>(R.id.playerMusicButton).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra(EXTRA_OPEN_MUSIC, true)
                }
            )
        }

        findViewById<TextView>(R.id.playerRadioButton).setOnClickListener {
            openCarScreen(RadioStationsActivity::class.java)
        }
        findViewById<TextView>(R.id.playerWhatsappButton).setOnClickListener {
            openCarScreen(WhatsAppConversationsActivity::class.java)
        }
        findViewById<TextView>(R.id.playerRouteButton).setOnClickListener {
            openCarScreen(RouteCarActivity::class.java)
        }
        findViewById<TextView>(R.id.playerParkingButton).setOnClickListener {
            openCarScreen(ParkingCarActivity::class.java)
        }
        findViewById<TextView>(R.id.playerSettingsButton).setOnClickListener {
            openCarScreen(CarSettingsActivity::class.java)
        }
        findViewById<TextView>(R.id.playerPhoneButton).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
            } catch (_: Exception) {
                Toast.makeText(this, "Telefoon-app niet gevonden", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.playerPlaylistButton).setOnClickListener {
            togglePlaylist()
        }
        findViewById<TextView>(R.id.playerPlaylistClose).setOnClickListener {
            playlistPanel.visibility = View.GONE
        }
    }

    private fun openCarScreen(clazz: Class<*>) {
        startActivity(
            Intent(this, clazz).putExtra(EXTRA_FROM_PLAYER, true)
        )
    }

    private fun configurePlaybackControls() {
        fun pauseOnly() {
            if (UsbPlaybackService.snapshot().isPlaying) {
                UsbPlaybackService.toggle(this)
                refreshPlayer()
            }
        }

        findViewById<TextView>(R.id.playerPauseA).setOnClickListener { pauseOnly() }
        findViewById<TextView>(R.id.playerPauseB).setOnClickListener { pauseOnly() }

        playPauseA.setOnClickListener {
            UsbPlaybackService.toggle(this)
            refreshPlayer()
        }

        findViewById<TextView>(R.id.playerPlayB).setOnClickListener {
            UsbPlaybackService.next(this)
            refreshPlayer()
        }

        val muteAction = View.OnClickListener {
            UsbPlaybackService.toggleMute(this)
            refreshPlayer()
        }
        muteA.setOnClickListener(muteAction)
        muteB.setOnClickListener(muteAction)

        findViewById<TextView>(R.id.playerFadeButton).setOnClickListener {
            UsbPlaybackService.fadeToNext(this)
        }

        autoButton.setOnClickListener {
            val enabled = !UsbPlaybackService.isAutoPlayEnabled(this)
            UsbPlaybackService.setAutoPlayEnabled(this, enabled)
            refreshPlayer()
        }

        findViewById<TextView>(R.id.playerPrevious).setOnClickListener {
            UsbPlaybackService.previous(this)
            refreshPlayer()
        }
        bottomPlayPause.setOnClickListener {
            UsbPlaybackService.toggle(this)
            refreshPlayer()
        }
        findViewById<TextView>(R.id.playerNext).setOnClickListener {
            UsbPlaybackService.next(this)
            refreshPlayer()
        }
    }

    private fun configureVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeSeek.max = max
        volumeSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        progress.coerceIn(0, max),
                        0
                    )
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        findViewById<TextView>(R.id.playerVolumeDown).setOnClickListener {
            val now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (now - 1).coerceAtLeast(0), 0)
            volumeSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        findViewById<TextView>(R.id.playerVolumeUp).setOnClickListener {
            val now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (now + 1).coerceAtMost(max), 0)
            volumeSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    private fun refreshPlayer() {
        val state = UsbPlaybackService.snapshot()
        val queue = UsbPlaybackService.queueSnapshot()
        val currentIndex = UsbPlaybackService.currentIndex()
        val next = if (currentIndex >= 0 && currentIndex + 1 < queue.size) {
            queue[currentIndex + 1]
        } else null

        currentTitle.text = if (state.hasTrack) state.title else "Geen nummer geselecteerd"
        bottomTitle.text = currentTitle.text
        currentWaveform.titleSeed = state.title
        currentWaveform.progress =
            if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs.toFloat() else 0f
        currentTime.text = "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}"

        nextTitle.text = next?.title ?: "Geen volgend nummer"
        nextWaveform.titleSeed = next?.title.orEmpty()
        nextWaveform.progress = 0f
        nextStatus.text = when {
            next == null -> "Einde afspeellijst"
            UsbPlaybackService.isAutoPlayEnabled(this) -> "Automatisch geladen • start vanzelf"
            else -> "Automatisch geladen • Auto staat uit"
        }

        val playLabel = if (state.isPlaying) "⏸" else "▶"
        playPauseA.text = playLabel
        bottomPlayPause.text = playLabel

        val muted = UsbPlaybackService.isMuted(this)
        val muteLabel = if (muted) "🔇 Muted" else "🔊 Mute"
        muteA.text = muteLabel
        muteB.text = muteLabel

        val auto = UsbPlaybackService.isAutoPlayEnabled(this)
        autoButton.text = if (auto) "Auto • Aan" else "Auto • Uit"
        autoButton.setTextColor(
            ContextCompat.getColor(
                this,
                if (auto) R.color.the_one_blue else R.color.gold
            )
        )

        volumeSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val signature =
            queue.joinToString("|") { it.uri + "\n" + it.title } + "#" + currentIndex
        if (signature != playlistSignature) {
            playlistSignature = signature
            rebuildPlaylist(queue, currentIndex)
        }
    }

    private fun togglePlaylist() {
        playlistPanel.visibility =
            if (playlistPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (playlistPanel.visibility == View.VISIBLE) {
            rebuildPlaylist(
                UsbPlaybackService.queueSnapshot(),
                UsbPlaybackService.currentIndex()
            )
        }
    }

    private fun rebuildPlaylist(
        queue: List<UsbPlaybackService.QueueItem>,
        currentIndex: Int
    ) {
        playlistList.removeAllViews()

        if (queue.isEmpty()) {
            playlistList.addView(TextView(this).apply {
                text = "Geen afspeellijst actief"
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                setPadding(10.dp, 18.dp, 10.dp, 18.dp)
            })
            return
        }

        queue.forEachIndexed { index, item ->
            val active = index == currentIndex
            val row = TextView(this).apply {
                text = if (active) "▶  ${index + 1}. ${item.title}" else "${index + 1}.  ${item.title}"
                textSize = 17f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (active) R.color.on_amber else R.color.text_main
                    )
                )
                setTypeface(
                    typeface,
                    if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                setPadding(12.dp, 11.dp, 12.dp, 11.dp)
                setBackgroundResource(
                    if (active) R.drawable.bg_amber_button else R.drawable.bg_outline
                )
                setOnClickListener {
                    UsbPlaybackService.play(
                        this@CarPlayerActivity,
                        queue,
                        index
                    )
                    refreshPlayer()
                }
            }
            playlistList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 7.dp }
            )
        }
    }

    private fun formatTime(ms: Int): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        refreshPlayer()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (CarMediaKeyHandler.handle(this, event)) {
            refreshPlayer()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
