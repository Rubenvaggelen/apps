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
 * Ingebouwde K2401 USB-speler.
 *
 * Belangrijk voor deze head-unit:
 * - USB 1 en USB 2 blijven afzonderlijke bronnen.
 * - Eerst wordt een USB-stick gekozen, daarna blader je door de echte mappen.
 * - We gebruiken MediaStore en vallen voor de mapnaam terug op het fysieke DATA-pad,
 *   omdat sommige K2401-ROMs RELATIVE_PATH leeg teruggeven en anders alle nummers
 *   ten onrechte in één platte lijst terechtkomen.
 */
class UsbMusicActivity : AppCompatActivity() {

    private data class Track(
        val uri: Uri,
        val displayName: String,
        val volumeKey: String,
        val folder: String
    )

    private data class UsbVolume(
        val key: String,
        val label: String
    )

    private data class BrowserEntry(
        val volumeKey: String? = null,
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
    private val usbVolumes = mutableListOf<UsbVolume>()
    private val browserEntries = mutableListOf<BrowserEntry>()
    private var playbackQueue: List<Track> = emptyList()
    private var currentVolumeKey: String? = null
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
                entry.track != null -> playTrack(entry.track)
                entry.folderPath != null -> {
                    currentFolder = entry.folderPath
                    rebuildBrowser()
                }
                entry.volumeKey != null -> {
                    currentVolumeKey = entry.volumeKey
                    currentFolder = ""
                    rebuildBrowser()
                }
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
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanUsbVolumes()
        } else {
            requestAudioPermission.launch(permission)
        }
    }

    private fun scanUsbVolumes() {
        stopPlayback(resetSelection = true)
        allTracks.clear()
        usbVolumes.clear()
        browserEntries.clear()
        currentVolumeKey = null
        currentFolder = ""
        listView.adapter = null
        statusText.text = "USB wordt gezocht…"
        pathText.text = "USB"
        chooseUsbButton.isEnabled = false
        refreshButton.isEnabled = false

        Thread {
            val found = mutableListOf<Track>()
            val detectedVolumeKeys = linkedSetOf<String>()

            if (Build.VERSION.SDK_INT >= 29) {
                val externalVolumes = try {
                    MediaStore.getExternalVolumeNames(this).toList()
                } catch (_: Exception) {
                    emptyList()
                }

                externalVolumes
                    .filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    .forEach { volume ->
                        detectedVolumeKeys += volume
                        queryAudioVolume(volume, found)
                    }
            }

            // Sommige K2401-ROMs melden USB-opslag niet als een eigen MediaStore-volume.
            // De legacy-query leest dan de fysieke paden en houdt USB 1/2 alsnog uit elkaar.
            if (detectedVolumeKeys.isEmpty() || found.isEmpty()) {
                queryLegacyRemovable(found)
                found.mapTo(detectedVolumeKeys) { it.volumeKey }
            }

            val keysWithTracks = found.map { it.volumeKey }.filter { it.isNotBlank() }.toSet()
            detectedVolumeKeys += keysWithTracks

            val orderedKeys = detectedVolumeKeys
                .filter { it.isNotBlank() && it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

            val volumes = orderedKeys.mapIndexed { index, key -> UsbVolume(key, "USB ${index + 1}") }

            found.sortWith(
                compareBy<Track> { it.volumeKey.lowercase(Locale.ROOT) }
                    .thenBy { it.folder.lowercase(Locale.ROOT) }
                    .thenBy { it.displayName.lowercase(Locale.ROOT) }
            )

            runOnUiThread {
                allTracks.clear()
                allTracks.addAll(found.distinctBy { it.uri.toString() })
                usbVolumes.clear()
                usbVolumes.addAll(volumes)
                chooseUsbButton.isEnabled = true
                refreshButton.isEnabled = true

                statusText.text = when {
                    usbVolumes.isEmpty() -> "Geen USB-opslag gevonden. Sluit je USB-stick aan en druk op Vernieuwen."
                    allTracks.isEmpty() -> "${usbVolumes.size} USB-stick${if (usbVolumes.size == 1) "" else "s"} gevonden, maar nog geen muziekbestanden geïndexeerd."
                    else -> "${usbVolumes.size} USB-stick${if (usbVolumes.size == 1) "" else "s"} gevonden • ${allTracks.size} nummer${if (allTracks.size == 1) "" else "s"}"
                }
                rebuildBrowser()
            }
        }.start()
    }

    private fun rebuildBrowser() {
        browserEntries.clear()
        val selectedVolume = currentVolumeKey

        if (selectedVolume == null) {
            usbVolumes.forEach { volume ->
                val count = allTracks.count { it.volumeKey == volume.key }
                browserEntries += BrowserEntry(
                    volumeKey = volume.key,
                    label = "🔌 ${volume.label}  •  $count nummer${if (count == 1) "" else "s"}"
                )
            }
            playbackQueue = emptyList()
            pathText.text = "USB"
            folderBackButton.visibility = View.GONE
            listView.adapter = ArrayAdapter(this, R.layout.view_usb_track, R.id.trackName, browserEntries.map { it.label })
            return
        }

        val prefix = currentFolder.trim('/')
        val volumeTracks = allTracks.filter { it.volumeKey == selectedVolume }
        val childFolders = linkedSetOf<String>()
        val directTracks = mutableListOf<Track>()

        volumeTracks.forEach { track ->
            val folder = track.folder.trim('/')
            if (prefix.isBlank()) {
                if (folder.isBlank()) {
                    directTracks += track
                } else {
                    childFolders += folder.substringBefore('/')
                }
            } else if (folder == prefix) {
                directTracks += track
            } else if (folder.startsWith("$prefix/")) {
                val rest = folder.removePrefix("$prefix/")
                val child = rest.substringBefore('/')
                if (child.isNotBlank()) childFolders += "$prefix/$child"
            }
        }

        childFolders.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { path ->
            browserEntries += BrowserEntry(
                volumeKey = selectedVolume,
                folderPath = path,
                label = "📁 ${path.substringAfterLast('/')}"
            )
        }
        directTracks.sortedBy { it.displayName.lowercase(Locale.ROOT) }.forEach { track ->
            browserEntries += BrowserEntry(track = track, label = "🎵 ${track.displayName}")
        }
        playbackQueue = directTracks.sortedBy { it.displayName.lowercase(Locale.ROOT) }

        val volumeLabel = usbVolumes.firstOrNull { it.key == selectedVolume }?.label ?: "USB"
        pathText.text = if (prefix.isBlank()) "$volumeLabel /" else "$volumeLabel / $prefix"
        folderBackButton.visibility = View.VISIBLE
        listView.adapter = ArrayAdapter(this, R.layout.view_usb_track, R.id.trackName, browserEntries.map { it.label })
    }

    private fun navigateUp() {
        val selectedVolume = currentVolumeKey ?: return
        val folder = currentFolder.trim('/')
        if (folder.isBlank()) {
            currentVolumeKey = null
            currentFolder = ""
        } else {
            currentVolumeKey = selectedVolume
            currentFolder = folder.substringBeforeLast('/', "")
        }
        rebuildBrowser()
    }

    /**
     * Query een apart MediaStore-volume. Eerst vragen we ook DATA op. Op de K2401
     * is RELATIVE_PATH soms leeg, terwijl DATA wel het echte USB-pad bevat.
     */
    private fun queryAudioVolume(volume: String, output: MutableList<Track>) {
        if (!queryAudioVolumeInternal(volume, output, includeData = true)) {
            queryAudioVolumeInternal(volume, output, includeData = false)
        }
    }

    @Suppress("DEPRECATION")
    private fun queryAudioVolumeInternal(volume: String, output: MutableList<Track>, includeData: Boolean): Boolean {
        val uri = try {
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(volume)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } catch (_: Exception) {
            return false
        }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME
        ).apply {
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (includeData) add(MediaStore.MediaColumns.DATA)
        }.toTypedArray()

        return try {
            contentResolver.query(
                uri,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC}!=0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val fileCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val dataCol = if (includeData) c.getColumnIndex(MediaStore.MediaColumns.DATA) else -1

                while (c.moveToNext() && output.size < 10000) {
                    val id = c.getLong(idCol)
                    val title = if (titleCol >= 0) c.getString(titleCol).orEmpty().trim() else ""
                    val artist = if (artistCol >= 0) c.getString(artistCol).orEmpty().trim() else ""
                    val file = if (fileCol >= 0) c.getString(fileCol).orEmpty().trim() else ""
                    val relative = if (pathCol >= 0) c.getString(pathCol).orEmpty() else ""
                    val rawPath = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""
                    val folder = when {
                        relative.isNotBlank() -> normalizeFolder(relative)
                        rawPath.isNotBlank() -> folderFromAbsolutePath(rawPath, volume)
                        else -> ""
                    }
                    val display = when {
                        title.isNotBlank() && artist.isNotBlank() && artist != "<unknown>" -> "$artist — $title"
                        title.isNotBlank() -> title
                        file.isNotBlank() -> file.substringBeforeLast('.')
                        else -> "Onbekend nummer"
                    }
                    output += Track(
                        uri = ContentUris.withAppendedId(uri, id),
                        displayName = display,
                        volumeKey = volume,
                        folder = folder
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLegacyRemovable(output: MutableList<Track>) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA
        )
        try {
            contentResolver.query(uri, projection, "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, null)?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val fileCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (c.moveToNext() && output.size < 10000) {
                    val rawPath = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""
                    val key = legacyVolumeKey(rawPath) ?: continue
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
                    output += Track(
                        uri = ContentUris.withAppendedId(uri, id),
                        displayName = display,
                        volumeKey = key,
                        folder = folderFromAbsolutePath(rawPath, key)
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun normalizeFolder(value: String): String = value.replace('\\', '/').trim('/').trim()

    /** Haal /storage/UUID of /mnt/media_rw/UUID uit de zichtbare mapstructuur. */
    private fun folderFromAbsolutePath(path: String, volumeKey: String): String {
        val clean = path.replace('\\', '/').substringBeforeLast('/', "")
        if (clean.isBlank()) return ""

        val lower = clean.lowercase(Locale.ROOT)
        val key = volumeKey.lowercase(Locale.ROOT)
        val keyIndex = lower.indexOf("/$key/")
        if (keyIndex >= 0) {
            return normalizeFolder(clean.substring(keyIndex + key.length + 2))
        }
        if (lower.endsWith("/$key")) return ""

        val prefixes = listOf("/mnt/media_rw/", "/storage/", "/mnt/usb_storage/", "/mnt/usb/")
        prefixes.forEach { prefix ->
            val index = lower.indexOf(prefix)
            if (index >= 0) {
                val afterRoot = clean.substring(index + prefix.length).trim('/')
                val withoutDevice = afterRoot.substringAfter('/', "")
                return normalizeFolder(withoutDevice)
            }
        }
        return normalizeFolder(clean)
    }

    /** Bepaal welke fysieke USB-stick bij een legacy pad hoort. */
    private fun legacyVolumeKey(path: String): String? {
        val clean = path.replace('\\', '/').trim()
        val lower = clean.lowercase(Locale.ROOT)
        if (clean.isBlank() || lower.contains("/storage/emulated/") || lower.contains("/storage/self/")) return null

        fun segmentAfter(prefix: String): String? {
            val i = lower.indexOf(prefix)
            if (i < 0) return null
            return clean.substring(i + prefix.length).trim('/').substringBefore('/').takeIf { it.isNotBlank() }
        }

        segmentAfter("/mnt/media_rw/")?.let { return it }
        segmentAfter("/storage/")?.let { root ->
            if (!root.equals("emulated", true) && !root.equals("self", true)) return root
        }
        segmentAfter("/mnt/usb_storage/")?.let { return it }
        segmentAfter("/mnt/usb/")?.let { return it }

        val parts = clean.split('/').filter { it.isNotBlank() }
        val usbPart = parts.firstOrNull {
            val p = it.lowercase(Locale.ROOT)
            p.startsWith("usb") || p.startsWith("udisk")
        }
        return usbPart
    }

    private fun playTrack(track: Track) {
        currentTrack = track
        stopPlayback(resetSelection = false)
        nowPlayingText.text = "Laden: ${track.displayName}"
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
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
            val first = playbackQueue.firstOrNull()
            if (first != null) playTrack(first)
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
        } catch (_: Exception) {}
    }

    private fun playNext() {
        val queue = playbackQueue
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.uri == currentTrack?.uri }
        playTrack(queue[if (index < 0) 0 else (index + 1) % queue.size])
    }

    private fun playPrevious() {
        val queue = playbackQueue
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
