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
 * K2401 USB-speler. We gebruiken bewust géén ACTION_OPEN_DOCUMENT_TREE meer:
 * de documentpicker van deze headunit springt terug naar de fabriekslauncher.
 * In plaats daarvan lezen we de door Android gemounte USB-mediavolumes direct
 * via MediaStore uit en spelen we de gevonden audio-URI's zelf af.
 */
class UsbMusicActivity : AppCompatActivity() {
    private data class Track(val uri: Uri, val displayName: String, val volume: String)

    private lateinit var statusText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var timeText: TextView
    private lateinit var listView: ListView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var chooseUsbButton: Button
    private lateinit var refreshButton: Button

    private val tracks = mutableListOf<Track>()
    private var player: MediaPlayer? = null
    private var currentIndex = -1
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
        nowPlayingText = findViewById(R.id.nowPlayingText)
        timeText = findViewById(R.id.timeText)
        listView = findViewById(R.id.trackList)
        seekBar = findViewById(R.id.seekBar)
        playPauseButton = findViewById(R.id.playPauseButton)
        chooseUsbButton = findViewById(R.id.chooseUsbButton)
        refreshButton = findViewById(R.id.refreshUsbButton)

        chooseUsbButton.text = "USB zoeken"
        refreshButton.text = "Vernieuwen"
        chooseUsbButton.setOnClickListener { ensurePermissionThenScan() }
        refreshButton.setOnClickListener { ensurePermissionThenScan() }
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
        val permission = when {
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_AUDIO
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanUsbVolumes()
        } else requestAudioPermission.launch(permission)
    }

    private fun scanUsbVolumes() {
        stopPlayback(resetSelection = true)
        tracks.clear()
        listView.adapter = null
        statusText.text = "USB wordt gezocht…"
        chooseUsbButton.isEnabled = false
        refreshButton.isEnabled = false

        Thread {
            val found = mutableListOf<Track>()
            val volumes = if (Build.VERSION.SDK_INT >= 29) {
                try { MediaStore.getExternalVolumeNames(this).toList() } catch (_: Exception) { emptyList() }
            } else listOf(MediaStore.VOLUME_EXTERNAL)

            // external_primary is de interne gedeelde opslag; alle overige
            // MediaStore-volumes zijn op deze K2401 USB/SD/removable volumes.
            val removable = if (Build.VERSION.SDK_INT >= 29) {
                volumes.filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
            } else volumes

            removable.forEach { volume ->
                queryAudioVolume(volume, found)
            }
            if (found.isEmpty()) queryLegacyRemovable(found)

            found.sortBy { it.displayName.lowercase(Locale.ROOT) }
            runOnUiThread {
                tracks.clear()
                tracks.addAll(found)
                listView.adapter = ArrayAdapter(
                    this,
                    R.layout.view_usb_track,
                    R.id.trackName,
                    tracks.map { it.displayName }
                )
                chooseUsbButton.isEnabled = true
                refreshButton.isEnabled = true
                statusText.text = when {
                    removable.isEmpty() -> "Geen USB-opslag gevonden. Sluit je USB-stick aan en druk op Vernieuwen."
                    tracks.isEmpty() -> "USB gevonden, maar geen muziekbestanden geïndexeerd. Wacht even en druk op Vernieuwen."
                    else -> "USB verbonden • ${tracks.size} nummer${if (tracks.size == 1) "" else "s"} gevonden"
                }
            }
        }.start()
    }

    private fun queryAudioVolume(volume: String, output: MutableList<Track>) {
        val uri = try {
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(volume)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } catch (_: Exception) { return }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
        try {
            contentResolver.query(uri, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val fileCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                while (c.moveToNext() && output.size < 4000) {
                    val id = c.getLong(idCol)
                    val title = if (titleCol >= 0) c.getString(titleCol).orEmpty().trim() else ""
                    val artist = if (artistCol >= 0) c.getString(artistCol).orEmpty().trim() else ""
                    val file = if (fileCol >= 0) c.getString(fileCol).orEmpty().trim() else ""
                    val display = when {
                        title.isNotBlank() && artist.isNotBlank() && artist != "<unknown>" -> "$artist — $title"
                        title.isNotBlank() -> title
                        file.isNotBlank() -> file.substringBeforeLast('.')
                        else -> "Onbekend nummer"
                    }
                    output += Track(ContentUris.withAppendedId(uri, id), display, volume)
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
                while (c.moveToNext() && output.size < 4000) {
                    val path = if (dataCol >= 0) c.getString(dataCol).orEmpty().lowercase(Locale.ROOT) else ""
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
                    output += Track(ContentUris.withAppendedId(uri, id), display, "legacy-usb")
                }
            }
        } catch (_: Exception) {}
    }

    private fun playTrack(index: Int) {
        if (index !in tracks.indices) return
        currentIndex = index
        val track = tracks[index]
        stopPlayback(resetSelection = false)
        nowPlayingText.text = "Laden: ${track.displayName}"
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
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
            if (tracks.isNotEmpty()) playTrack(if (currentIndex in tracks.indices) currentIndex else 0)
            return
        }
        try {
            if (mp.isPlaying) { mp.pause(); playPauseButton.text = "▶ Afspelen" }
            else { mp.start(); playPauseButton.text = "⏸ Pauze" }
        } catch (_: Exception) {}
    }

    private fun playNext() {
        if (tracks.isEmpty()) return
        playTrack(if (currentIndex !in tracks.indices) 0 else (currentIndex + 1) % tracks.size)
    }

    private fun playPrevious() {
        if (tracks.isEmpty()) return
        playTrack(if (currentIndex !in tracks.indices) 0 else (currentIndex - 1 + tracks.size) % tracks.size)
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
            currentIndex = -1
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
