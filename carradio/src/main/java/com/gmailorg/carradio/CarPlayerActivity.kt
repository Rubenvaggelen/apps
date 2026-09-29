package com.gmailorg.carradio

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.DragEvent
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

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
    private lateinit var masterVolumeLabel: TextView
    private lateinit var whatsappButton: TextView
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
        masterVolumeLabel = findViewById(R.id.playerMasterVolumeLabel)
        whatsappButton = findViewById(R.id.playerWhatsappButton)

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
        whatsappButton.setOnClickListener {
            DashboardUnreadStore.clear(this)
            refreshWhatsappBadge()
            openCarScreen(WhatsAppConversationsActivity::class.java)
        }
        findViewById<TextView>(R.id.playerSettingsButton).setOnClickListener {
            openCarScreen(CarSettingsActivity::class.java)
        }

        findViewById<TextView>(R.id.playerPlaylistButton).setOnClickListener {
            togglePlaylist()
        }
        findViewById<TextView>(R.id.playerAddButton).setOnClickListener {
            showAddMusicMenu()
        }
        findViewById<TextView>(R.id.playerPlaylistAdd).setOnClickListener {
            showAddMusicMenu()
        }
        findViewById<TextView>(R.id.playerPlaylistClear).setOnClickListener {
            clearPlaylist()
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


    private fun clearPlaylist() {
        val queue = UsbPlaybackService.queueSnapshot()
        if (queue.isEmpty()) {
            Toast.makeText(this, "De afspeellijst is al leeg.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Afspeellijst wissen?")
            .setMessage("Alle nummers worden uit de player verwijderd en de muziek stopt.")
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Wissen") { _, _ ->
                UsbPlaybackService.stop(this)
                playlistSignature = ""
                handler.postDelayed({
                    refreshPlayer()
                    rebuildPlaylist(emptyList(), -1)
                }, 120L)
                Toast.makeText(this, "Afspeellijst gewist", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showAddMusicMenu() {
        AlertDialog.Builder(this)
            .setTitle("Muziek toevoegen uit Shared Media")
            .setItems(arrayOf("Losse nummers", "Complete map")) { _, which ->
                val mode = if (which == 0) {
                    SharedMediaImportActivity.MODE_TRACKS
                } else {
                    SharedMediaImportActivity.MODE_FOLDER
                }
                startActivity(
                    Intent(this, SharedMediaImportActivity::class.java)
                        .putExtra(SharedMediaImportActivity.EXTRA_MODE, mode)
                )
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun addImportedUris(uris: List<Uri>) {
        val items = uris.map { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val title = queryDisplayName(uri).ifBlank {
                uri.lastPathSegment?.substringAfterLast('/') ?: "Muziek"
            }
            UsbPlaybackService.QueueItem(uri.toString(), title)
        }
        appendImportedItems(items)
    }

    private fun importFolder(treeUri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        Toast.makeText(this, "Map wordt geladen…", Toast.LENGTH_SHORT).show()

        Thread {
            val root = DocumentFile.fromTreeUri(this, treeUri)
            val audioFiles = if (root == null) emptyList() else collectAudioFiles(root)
            val items = audioFiles
                .sortedBy { it.name.orEmpty().lowercase() }
                .map {
                    UsbPlaybackService.QueueItem(
                        it.uri.toString(),
                        it.name?.substringBeforeLast('.', it.name.orEmpty()).orEmpty()
                            .ifBlank { "Muziek" }
                    )
                }

            runOnUiThread {
                appendImportedItems(items)
            }
        }.start()
    }

    private fun collectAudioFiles(root: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)

        while (stack.isNotEmpty() && result.size < 5000) {
            val folder = stack.removeLast()
            folder.listFiles().forEach { file ->
                when {
                    file.isDirectory -> stack.add(file)
                    file.isFile && isAudioFile(file) -> result += file
                }
            }
        }
        return result
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        if (file.type?.startsWith("audio/", ignoreCase = true) == true) return true
        val ext = file.name.orEmpty().substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "mp3", "wav", "flac", "m4a", "aac", "ogg", "oga",
            "opus", "wma", "aif", "aiff", "alac", "mka", "ac3", "amr"
        )
    }

    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun appendImportedItems(items: List<UsbPlaybackService.QueueItem>) {
        if (items.isEmpty()) {
            Toast.makeText(this, "Geen muziek gevonden.", Toast.LENGTH_SHORT).show()
            return
        }
        val added = UsbPlaybackService.append(this, items)
        playlistSignature = ""
        refreshPlayer()
        playlistPanel.visibility = View.VISIBLE
        Toast.makeText(
            this,
            "$added nummer${if (added == 1) "" else "s"} toegevoegd",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun configurePlaybackControls() {
        findViewById<TextView>(R.id.playerPauseA).setOnClickListener {
            UsbPlaybackService.pauseDeckA(this)
            refreshPlayer()
        }

        findViewById<TextView>(R.id.playerPauseB).setOnClickListener {
            UsbPlaybackService.pauseDeckB(this)
            refreshPlayer()
        }

        playPauseA.setOnClickListener {
            UsbPlaybackService.toggle(this)
            refreshPlayer()
        }

        findViewById<TextView>(R.id.playerPlayB).setOnClickListener {
            UsbPlaybackService.toggleDeckB(this)
            refreshPlayer()
        }

        muteA.setOnClickListener {
            UsbPlaybackService.toggleMute(this)
            refreshPlayer()
        }

        muteB.setOnClickListener {
            UsbPlaybackService.toggleDeckBMute(this)
            refreshPlayer()
        }

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
        volumeSeek.max = CarVolumeControl.max(this)
        refreshMasterVolumeUi()

        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                CarVolumeControl.set(this@CarPlayerActivity, progress)
                refreshMasterVolumeUi()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                refreshMasterVolumeUi()
            }
        })

        findViewById<TextView>(R.id.playerVolumeDown).setOnClickListener {
            CarVolumeControl.lower(this)
            refreshMasterVolumeUi()
        }

        findViewById<TextView>(R.id.playerVolumeUp).setOnClickListener {
            CarVolumeControl.raise(this)
            refreshMasterVolumeUi()
        }

        masterVolumeLabel.setOnClickListener {
            CarVolumeControl.toggleMute(this)
            refreshMasterVolumeUi()
        }
    }

    private fun refreshPlayer() {
        val state = UsbPlaybackService.snapshot()
        val deckBState = UsbPlaybackService.deckBSnapshot()
        val queue = UsbPlaybackService.queueSnapshot()
        val currentIndex = UsbPlaybackService.currentIndex()

        currentTitle.text = if (state.hasTrack) state.title else "Geen nummer geselecteerd"
        bottomTitle.text = currentTitle.text
        currentWaveform.titleSeed = state.title
        currentWaveform.progress =
            if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs.toFloat() else 0f
        currentTime.text = "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}"

        nextTitle.text = if (deckBState.hasTrack) deckBState.title else "Geen volgend nummer"
        nextWaveform.titleSeed = deckBState.title
        nextWaveform.progress =
            if (deckBState.durationMs > 0) {
                deckBState.positionMs.toFloat() / deckBState.durationMs.toFloat()
            } else 0f
        nextStatus.text = when {
            !deckBState.hasTrack -> "B • GEEN TRACK"
            deckBState.isPreparing ->
                "B • LADEN • ${formatTime(deckBState.positionMs)}"
            deckBState.isPlaying ->
                "B • PLAYING • ${formatTime(deckBState.positionMs)} / ${formatTime(deckBState.durationMs)}"
            else ->
                "B • READY/PAUZE • ${formatTime(deckBState.positionMs)} / ${formatTime(deckBState.durationMs)}"
        }

        val playLabel = if (state.isPlaying) "⏸" else "▶"
        playPauseA.text = playLabel
        bottomPlayPause.text = playLabel

        muteA.text =
            if (UsbPlaybackService.isMuted(this)) "🔇 Muted" else "🔊 Mute"
        muteB.text =
            if (UsbPlaybackService.isDeckBMuted(this)) "🔇 Muted" else "🔊 Mute"

        val auto = UsbPlaybackService.isAutoPlayEnabled(this)
        autoButton.text = if (auto) "Auto • Aan" else "Auto • Uit"
        autoButton.setTextColor(
            ContextCompat.getColor(
                this,
                if (auto) R.color.the_one_blue else R.color.gold
            )
        )

        refreshWhatsappBadge()
        refreshMasterVolumeUi()

        val signature =
            queue.joinToString("|") { it.uri + "\n" + it.title } + "#" + currentIndex
        if (signature != playlistSignature) {
            playlistSignature = signature
            rebuildPlaylist(queue, currentIndex)
        }
    }

    private fun refreshWhatsappBadge() {
        val unread = DashboardUnreadStore.count(this)
        whatsappButton.text = if (unread > 0) "WhatsApp  ● " + unread else "WhatsApp"
        whatsappButton.setTextColor(
            ContextCompat.getColor(
                this,
                if (unread > 0) R.color.gold else R.color.text_main
            )
        )
        whatsappButton.setBackgroundResource(
            if (unread > 0) R.drawable.bg_gold_outline else R.drawable.bg_outline
        )
    }

    private fun refreshMasterVolumeUi() {
        val max = CarVolumeControl.max(this)
        val current = CarVolumeControl.current(this).coerceIn(0, max)
        volumeSeek.max = max
        volumeSeek.progress = current
        masterVolumeLabel.text = "MASTER • ${current * 100 / max}%"
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
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val dragHandle = TextView(this).apply {
                text = "☰"
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.the_one_blue))
                setBackgroundResource(R.drawable.bg_outline)
                setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                contentDescription = "Sleep ${item.title}"
                setOnLongClickListener {
                    val clip = ClipData.newPlainText("playlist_index", index.toString())
                    val shadow = View.DragShadowBuilder(row)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        startDragAndDrop(clip, shadow, index, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        startDrag(clip, shadow, index, 0)
                    }
                    true
                }
            }
            row.addView(
                dragHandle,
                LinearLayout.LayoutParams(
                    52.dp,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { marginEnd = 7.dp }
            )

            val title = TextView(this).apply {
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
                setPadding(12.dp, 11.dp, 10.dp, 11.dp)
                setBackgroundResource(
                    if (active) R.drawable.bg_amber_button else R.drawable.bg_outline
                )
                setOnClickListener {
                    UsbPlaybackService.play(
                        this@CarPlayerActivity,
                        UsbPlaybackService.queueSnapshot(),
                        index
                    )
                    playlistSignature = ""
                    refreshPlayer()
                    Toast.makeText(
                        this@CarPlayerActivity,
                        "Speelt: ${item.title}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            row.addView(
                title,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            row.addView(
                TextView(this).apply {
                    text = "WISSEN"
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.gold))
                    setBackgroundResource(R.drawable.bg_gold_outline)
                    setPadding(10.dp, 10.dp, 10.dp, 10.dp)
                    setOnClickListener {
                        val removed = UsbPlaybackService.removeAt(
                            this@CarPlayerActivity,
                            index
                        )
                        if (removed) {
                            playlistSignature = ""
                            refreshPlayer()
                            Toast.makeText(
                                this@CarPlayerActivity,
                                "Verwijderd: ${item.title}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    82.dp,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { marginStart = 7.dp }
            )

            row.setOnDragListener { view, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED ->
                        event.clipDescription?.hasMimeType("text/plain") == true

                    DragEvent.ACTION_DRAG_ENTERED -> {
                        view.alpha = 0.65f
                        true
                    }

                    DragEvent.ACTION_DRAG_EXITED -> {
                        view.alpha = 1f
                        true
                    }

                    DragEvent.ACTION_DROP -> {
                        view.alpha = 1f
                        val from = event.localState as? Int ?: return@setOnDragListener false
                        if (from == index) return@setOnDragListener true

                        val moved = UsbPlaybackService.move(
                            this@CarPlayerActivity,
                            from,
                            index
                        )
                        if (moved) {
                            playlistSignature = ""
                            rebuildPlaylist(
                                UsbPlaybackService.queueSnapshot(),
                                UsbPlaybackService.currentIndex()
                            )
                            refreshPlayer()
                        }
                        moved
                    }

                    DragEvent.ACTION_DRAG_ENDED -> {
                        view.alpha = 1f
                        true
                    }

                    else -> true
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
