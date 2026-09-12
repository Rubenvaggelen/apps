package com.gmailorg.carradio

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Ingebouwde K2401 USB-speler met mappenbrowser. We gebruiken MediaStore in
 * plaats van de systeem-documentpicker, omdat die picker op deze headunit naar
 * de fabriekslauncher terug kan springen.
 */
class UsbMusicActivity : AppCompatActivity() {
    private data class Track(
        val uri: Uri,
        val displayName: String,
        val volume: String,
        val folder: String
    )

    private data class BrowserEntry(
        val folderPath: String? = null,
        val track: Track? = null,
        val label: String
    )

    private lateinit var statusText: TextView
    private lateinit var pathText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var timeText: TextView
    private lateinit var listView: ListView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var chooseUsbButton: Button
    private lateinit var refreshButton: Button
    private lateinit var folderBackButton: Button

    private val allTracks = mutableListOf<Track>()
    private val browserEntries = mutableListOf<BrowserEntry>()
    private var playbackQueue: List<Track> = emptyList()
    private var currentFolder = ""
    private var player: MediaPlayer? = null
    private var currentTrack: Track? = null
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanUsbVolumes()
        else statusText.text = "Geef The One toegang tot audio om USB-muziek te lezen."
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
        pathText = findViewById(R.id.usbPathText)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        timeText = findViewById(R.id.timeText)
        listView = findViewById(R.id.trackList)
        seekBar = findViewById(R.id.seekBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        chooseUsbButton = findViewById(R.id.chooseUsbButton)
        refreshButton = findViewById(R.id.refreshUsbButton)
        folderBackButton = findViewById(R.id.folderBackButton)

        chooseUsbButton.text = "USB zoeken"
        refreshButton.text = "Vernieuwen"
        chooseUsbButton.setOnClickListener { ensurePermissionThenScan() }
        refreshButton.setOnClickListener { ensurePermissionThenScan() }
        folderBackButton.setOnClickListener { navigateUp() }
        findViewById<Button>(R.id.previousButton).setOnClickListener { playPrevious() }
        playPauseButton.setOnClickListener { togglePlayPause() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { playNext() }
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = browserEntries.getOrNull(position) ?: return@setOnItemClickListener
            when {
                entry.folderPath != null -> {
                    currentFolder = entry.folderPath
                    rebuildBrowser()
                }
                entry.track != null -> playTrack(entry.track)
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = try { player?.duration ?: 0 } catch (_: IllegalStateException) { 0 }
                    timeText.text = "${formatTime(progress)} / ${formatTime(duration)}"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                try { player?.seekTo(seekBar?.progress ?: 0) } catch (_: Exception) {}
                userSeeking = false
            }
        })

        handler.post(progressTick)
        ensurePermissionThenScan()
    }

    private fun ensurePermissionThenScan() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) scanUsbVolumes()
        else requestAudioPermission.launch(permission)
    }

    private fun scanUsbVolumes() {
        stopPlayback(resetSelection = true)
        allTracks.clear()
        browserEntries.clear()
        currentFolder = ""
        listView.adapter = null
        statusText.text = "USB wordt gezocht…"
        pathText.text = "USB /"
        chooseUsbButton.isEnabled = false
        refreshButton.isEnabled = false

        Thread {
            val found = mutableListOf<Track>()
            val volumes = if (Build.VERSION.SDK_INT >= 29) {
                try { MediaStore.getExternalVolumeNames(this).toList() } catch (_: Exception) { emptyList() }
            } else listOf(MediaStore.VOLUME_EXTERNAL)

            val removable = if (Build.VERSION.SDK_INT >= 29) volumes.filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY } else volumes
            removable.forEach { queryAudioVolume(it, found) }
            if (found.isEmpty()) queryLegacyRemovable(found)
            found.sortWith(compareBy<Track> { it.folder.lowercase(Locale.ROOT) }.thenBy { it.displayName.lowercase(Locale.ROOT) })

            runOnUiThread {
                allTracks.clear()
                allTracks.addAll(found)
                chooseUsbButton.isEnabled = true
                refreshButton.isEnabled = true
                statusText.text = when {
                    removable.isEmpty() && found.isEmpty() -> "Geen USB-opslag gevonden. Sluit je USB-stick aan en druk op Vernieuwen."
                    found.isEmpty() -> "USB gevonden, maar nog geen muziekbestanden geïndexeerd. Wacht even en druk op Vernieuwen."
                    else -> "USB verbonden • ${found.size} nummer${if (found.size == 1) "" else "s"} gevonden"
                }
                rebuildBrowser()
            }
        }.start()
    }

    private fun rebuildBrowser() {
        browserEntries.clear()
        val prefix = currentFolder.trim('/')
        val childFolders = linkedSetOf<String>()
        val directTracks = mutableListOf<Track>()

        allTracks.forEach { track ->
            val folder = track.folder.trim('/')
            if (prefix.isBlank()) {
                if (folder.isBlank()) directTracks += track
                else childFolders += folder.substringBefore('/')
            } else if (folder == prefix) {
                directTracks += track
            } else if (folder.startsWith("$prefix/")) {
                val rest = folder.removePrefix("$prefix/")
                val child = rest.substringBefore('/')
                if (child.isNotBlank()) childFolders += "$prefix/$child"
            }
        }

        childFolders.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { path ->
            browserEntries += BrowserEntry(folderPath = path, label = "📁 ${path.substringAfterLast('/')}")
        }
        directTracks.sortedBy { it.displayName.lowercase(Locale.ROOT) }.forEach { track ->
            browserEntries += BrowserEntry(track = track, label = "🎵 ${track.displayName}")
        }
        playbackQueue = directTracks.sortedBy { it.displayName.lowercase(Locale.ROOT) }

        listView.adapter = ArrayAdapter(this, R.layout.view_usb_track, R.id.trackName, browserEntries.map { it.label })
        pathText.text = if (prefix.isBlank()) "USB /" else "USB / $prefix"
        folderBackButton.visibility = if (prefix.isBlank()) View.GONE else View.VISIBLE
    }

    private fun navigateUp() {
        val folder = currentFolder.trim('/')
        if (folder.isBlank()) return
        currentFolder = folder.substringBeforeLast('/', "")
        rebuildBrowser()
    }

    private fun queryAudioVolume(volume: String, output: MutableList<Track>) {
        val uri = try {
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(volume) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } catch (_: Exception) { return }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME
        ).apply {
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()

        try {
            contentResolver.query(uri, projection, "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val fileCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                while (c.moveToNext() && output.size < 5000) {
                    val id = c.getLong(idCol)
                    val title = if (titleCol >= 0) c.getString(titleCol).orEmpty().trim() else ""
                    val artist = if (artistCol >= 0) c.getString(artistCol).orEmpty().trim() else ""
                    val file = if (fileCol >= 0) c.getString(fileCol).orEmpty().trim() else ""
                    val folder = if (pathCol >= 0) normalizeFolder(c.getString(pathCol).orEmpty()) else ""
                    val display = when {
                        title.isNotBlank() && artist.isNotBlank() && artist != "<unknown>" -> "$artist — $title"
                        title.isNotBlank() -> title
                        file.isNotBlank() -> file.substringBeforeLast('.')
                        else -> "Onbekend nummer"
                    }
                    output += Track(ContentUris.withAppendedId(uri, id), display, volume, folder)
                }
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun queryLegacyRemovable(output: MutableList<Track>) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA)
        try {
            contentResolver.query(uri, projection, "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, null)?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val fileCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext() && output.size < 5000) {
                    val rawPath = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""
                    val path = rawPath.lowercase(Locale.ROOT)
                    val removablePath = path.contains("/usb") || path.contains("/mnt/media_rw/") || (path.startsWith("/storage/") && !path.contains("/emulated/"))
                    if (!removablePath) continue
                    val id = c.getLong(idCol)
                    val title = if (titleCol >= 0) c.getString(titleCol).orEmpty().trim() else ""
                    val artist = if (artistCol >= 0) c.getString(artistCol).orEmpty().trim() else ""
                    val file = if (fileCol >= 0) c.getString(fileCol).orEmpty().trim() else ""
                    val display = when {
                        title.isNotBlank() && artist.isNotBlank() && artist != "<unknown>" -> "$artist — $title"
                        title.isNotBlank() -> title
                        else -> file.substringBeforeLast('.').ifBlank { "Onbekend nummer" }
                    }
                    output += Track(ContentUris.withAppendedId(uri, id), display, "legacy-usb", legacyFolder(rawPath))
                }
            }
        } catch (_: Exception) {}
    }

    private fun normalizeFolder(value: String): String = value.replace('\\', '/').trim('/').trim()

    private fun legacyFolder(path: String): String {
        val clean = path.replace('\\', '/')
        val relative = when {
            clean.startsWith("/storage/") -> clean.removePrefix("/storage/").substringAfter('/', "")
            clean.contains("/mnt/media_rw/") -> clean.substringAfter("/mnt/media_rw/").substringAfter('/', "")
            else -> clean.substringAfterLast("/usb/", clean)
        }
        return normalizeFolder(relative.substringBeforeLast('/', ""))
    }

    private fun playTrack(track: Track) {
        currentTrack = track
        stopPlayback(resetSelection = false)
        nowPlayingText.text = "Laden: ${track.displayName}"
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(this, track.uri)
            mp.setOnPreparedListener {
                seekBar.max = it.duration.coerceAtLeast(1)
                nowPlayingText.text = track.displayName
                it.start()
                playPauseButton.text = "⏸ Pauze"
            }
            mp.setOnCompletionListener { playNext() }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Dit nummer kon niet worden afgespeeld", Toast.LENGTH_SHORT).show()
                playPauseButton.text = "▶ Afspelen"
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            try { mp.release() } catch (_: Exception) {}
            if (player === mp) player = null
            Toast.makeText(this, "Kon dit USB-nummer niet openen", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayPause() {
        val mp = player
        if (mp == null) {
            val first = playbackQueue.firstOrNull() ?: allTracks.firstOrNull()
            if (first != null) playTrack(first)
            return
        }
        try {
            if (mp.isPlaying) { mp.pause(); playPauseButton.text = "▶ Afspelen" }
            else { mp.start(); playPauseButton.text = "⏸ Pauze" }
        } catch (_: Exception) {}
    }

    private fun playNext() {
        val queue = if (playbackQueue.isNotEmpty()) playbackQueue else allTracks
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.uri == currentTrack?.uri }
        playTrack(queue[if (index < 0) 0 else (index + 1) % queue.size])
    }

    private fun playPrevious() {
        val queue = if (playbackQueue.isNotEmpty()) playbackQueue else allTracks
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.uri == currentTrack?.uri }
        playTrack(queue[if (index < 0) 0 else (index - 1 + queue.size) % queue.size])
    }

    private fun stopPlayback(resetSelection: Boolean) {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        seekBar.progress = 0
        timeText.text = "0:00 / 0:00"
        playPauseButton.text = "▶ Afspelen"
        if (resetSelection) {
            currentTrack = null
            nowPlayingText.text = "Geen nummer geselecteerd"
        }
    }

    private fun formatTime(ms: Int): String {
        val sec = ms.coerceAtLeast(0) / 1000
        return "%d:%02d".format(Locale.ROOT, sec / 60, sec % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTick)
        stopPlayback(resetSelection = false)
        super.onDestroy()
    }
}
