package com.gmailorg.carradio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/**
 * Ingebouwde USB-muziekspeler voor de K2401.
 * De gebruiker kiest de USB-root één keer via Androids opslagkiezer. De read-toegang
 * wordt persistent opgeslagen zodat The One de stick bij volgende ritten opnieuw kan lezen.
 */
class UsbMusicActivity : AppCompatActivity() {

    private data class Track(
        val uri: Uri,
        val fileName: String,
        val displayName: String
    )

    private lateinit var statusText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var timeText: TextView
    private lateinit var listView: ListView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var chooseUsbButton: Button
    private lateinit var refreshButton: Button

    private val tracks = mutableListOf<Track>()
    private var adapter: ArrayAdapter<String>? = null
    private var player: MediaPlayer? = null
    private var currentIndex = -1
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val usbPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            if (tracks.isEmpty()) statusText.text = "Geen USB-opslag gekozen"
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Sommige K2401-ROMs geven de persistable flag niet netjes door. De huidige sessie werkt dan nog wel.
        }
        prefs.edit().putString(KEY_USB_URI, uri.toString()).apply()
        scanUsb(uri)
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null) {
                try {
                    if (!userSeeking && mp.duration > 0) {
                        seekBar.max = mp.duration
                        seekBar.progress = mp.currentPosition
                        timeText.text = "${formatTime(mp.currentPosition)} / ${formatTime(mp.duration)}"
                    }
                } catch (_: IllegalStateException) {}
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_music)

        statusText = findViewById(R.id.usbStatusText)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        timeText = findViewById(R.id.timeText)
        listView = findViewById(R.id.trackList)
        seekBar = findViewById(R.id.seekBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        chooseUsbButton = findViewById(R.id.chooseUsbButton)
        refreshButton = findViewById(R.id.refreshUsbButton)

        chooseUsbButton.setOnClickListener { chooseUsb() }
        refreshButton.setOnClickListener { loadSavedUsbOrChoose(forceChoose = false) }
        findViewById<Button>(R.id.previousButton).setOnClickListener { playPrevious() }
        playPauseButton.setOnClickListener { togglePlayPause() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { playNext() }

        listView.setOnItemClickListener { _, _, position, _ -> playTrack(position) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = try { player?.duration ?: 0 } catch (_: IllegalStateException) { 0 }
                    timeText.text = "${formatTime(progress)} / ${formatTime(duration)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val mp = player
                if (mp != null) {
                    try { mp.seekTo(seekBar?.progress ?: 0) } catch (_: IllegalStateException) {}
                }
                userSeeking = false
            }
        })

        handler.post(progressTick)
        loadSavedUsbOrChoose(forceChoose = false)
    }

    private fun chooseUsb() {
        usbPicker.launch(null)
    }

    private fun loadSavedUsbOrChoose(forceChoose: Boolean) {
        val saved = prefs.getString(KEY_USB_URI, null)
        if (forceChoose || saved.isNullOrBlank()) {
            statusText.text = "Kies je USB-stick. Dit hoeft normaal maar één keer."
            chooseUsb()
            return
        }
        val uri = try { Uri.parse(saved) } catch (_: Exception) { null }
        if (uri == null) {
            prefs.edit().remove(KEY_USB_URI).apply()
            chooseUsb()
            return
        }
        scanUsb(uri)
    }

    private fun scanUsb(rootUri: Uri) {
        stopPlayback(resetSelection = true)
        tracks.clear()
        listView.adapter = null
        statusText.text = "USB wordt uitgelezen…"
        refreshButton.isEnabled = false
        chooseUsbButton.isEnabled = false

        Thread {
            val found = mutableListOf<Track>()
            try {
                val root = DocumentFile.fromTreeUri(this, rootUri)
                if (root == null || !root.exists() || !root.canRead()) {
                    throw IllegalStateException("USB is niet leesbaar")
                }
                collectAudioFiles(root, found, depth = 0)
                found.sortBy { it.displayName.lowercase(Locale.ROOT) }
            } catch (_: Exception) {
                runOnUiThread {
                    refreshButton.isEnabled = true
                    chooseUsbButton.isEnabled = true
                    statusText.text = "USB kon niet worden gelezen. Kies de USB opnieuw."
                    Toast.makeText(this, "USB-toegang is niet meer geldig", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            runOnUiThread {
                tracks.clear()
                tracks.addAll(found)
                adapter = ArrayAdapter(
                    this,
                    R.layout.view_usb_track,
                    R.id.trackName,
                    tracks.map { it.displayName }
                )
                listView.adapter = adapter
                refreshButton.isEnabled = true
                chooseUsbButton.isEnabled = true
                statusText.text = if (tracks.isEmpty()) {
                    "USB gelezen • geen ondersteunde muziekbestanden gevonden"
                } else {
                    "USB verbonden • ${tracks.size} nummer${if (tracks.size == 1) "" else "s"} gevonden"
                }
            }
        }.start()
    }

    private fun collectAudioFiles(file: DocumentFile, output: MutableList<Track>, depth: Int) {
        if (depth > MAX_SCAN_DEPTH || output.size >= MAX_TRACKS) return
        val children = try { file.listFiles() } catch (_: Exception) { emptyArray() }
        for (child in children) {
            if (output.size >= MAX_TRACKS) return
            if (child.isDirectory) {
                collectAudioFiles(child, output, depth + 1)
            } else if (child.isFile && isAudioFile(child)) {
                val fileName = child.name ?: "Onbekend nummer"
                val displayName = readDisplayName(child.uri, fileName)
                output += Track(child.uri, fileName, displayName)
            }
        }
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val mime = file.type?.lowercase(Locale.ROOT).orEmpty()
        if (mime.startsWith("audio/")) return true
        val name = file.name?.lowercase(Locale.ROOT).orEmpty()
        return AUDIO_EXTENSIONS.any { name.endsWith(it) }
    }

    private fun readDisplayName(uri: Uri, fallback: String): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
            when {
                title.isNotBlank() && artist.isNotBlank() -> "$artist — $title"
                title.isNotBlank() -> title
                else -> fallback.substringBeforeLast('.')
            }
        } catch (_: Exception) {
            fallback.substringBeforeLast('.')
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun playTrack(index: Int) {
        if (index !in tracks.indices) return
        currentIndex = index
        val track = tracks[index]
        stopPlayback(resetSelection = false)
        nowPlayingText.text = "Laden: ${track.displayName}"
        playPauseButton.text = "⏸ Pauze"

        val newPlayer = MediaPlayer()
        player = newPlayer
        try {
            newPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            newPlayer.setDataSource(this, track.uri)
            newPlayer.setOnPreparedListener { mp ->
                seekBar.max = mp.duration.coerceAtLeast(1)
                nowPlayingText.text = track.displayName
                try { mp.start() } catch (_: IllegalStateException) {}
                playPauseButton.text = "⏸ Pauze"
            }
            newPlayer.setOnCompletionListener { playNext() }
            newPlayer.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Dit nummer kon niet worden afgespeeld", Toast.LENGTH_SHORT).show()
                playPauseButton.text = "▶ Afspelen"
                true
            }
            newPlayer.prepareAsync()
        } catch (_: Exception) {
            try { newPlayer.release() } catch (_: Exception) {}
            if (player === newPlayer) player = null
            playPauseButton.text = "▶ Afspelen"
            Toast.makeText(this, "Kon ${track.fileName} niet openen", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayPause() {
        val mp = player
        if (mp == null) {
            if (tracks.isNotEmpty()) playTrack(if (currentIndex in tracks.indices) currentIndex else 0)
            return
        }
        try {
            if (mp.isPlaying) {
                mp.pause()
                playPauseButton.text = "▶ Afspelen"
            } else {
                mp.start()
                playPauseButton.text = "⏸ Pauze"
            }
        } catch (_: IllegalStateException) {}
    }

    private fun playNext() {
        if (tracks.isEmpty()) return
        val next = if (currentIndex !in tracks.indices) 0 else (currentIndex + 1) % tracks.size
        playTrack(next)
    }

    private fun playPrevious() {
        if (tracks.isEmpty()) return
        val previous = if (currentIndex !in tracks.indices) 0 else (currentIndex - 1 + tracks.size) % tracks.size
        playTrack(previous)
    }

    private fun stopPlayback(resetSelection: Boolean) {
        val old = player
        player = null
        if (old != null) {
            try { old.stop() } catch (_: Exception) {}
            try { old.reset() } catch (_: Exception) {}
            try { old.release() } catch (_: Exception) {}
        }
        seekBar.progress = 0
        timeText.text = "0:00 / 0:00"
        playPauseButton.text = "▶ Afspelen"
        if (resetSelection) {
            currentIndex = -1
            nowPlayingText.text = "Geen nummer geselecteerd"
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = (ms.coerceAtLeast(0) / 1000)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTick)
        stopPlayback(resetSelection = false)
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "the_one_usb_music"
        private const val KEY_USB_URI = "usb_tree_uri"
        private const val MAX_SCAN_DEPTH = 12
        private const val MAX_TRACKS = 3000
        private val AUDIO_EXTENSIONS = listOf(
            ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma", ".amr"
        )
    }
}
