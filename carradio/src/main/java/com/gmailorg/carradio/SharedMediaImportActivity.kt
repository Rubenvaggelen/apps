package com.gmailorg.carradio

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.concurrent.Executors

class SharedMediaImportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "shared_media_import_mode"
        const val MODE_TRACKS = "tracks"
        const val MODE_FOLDER = "folder"
    }

    private val io = Executors.newSingleThreadExecutor()
    private var mode = MODE_TRACKS
    private var sticks: List<RemoteUsbMusicClient.RemoteStick> = emptyList()
    private var currentStick: RemoteUsbMusicClient.RemoteStick? = null
    private var currentFolder = ""
    private val selected = linkedMapOf<String, RemoteUsbMusicClient.RemoteFile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mode = intent.getStringExtra(EXTRA_MODE).orEmpty().ifBlank { MODE_TRACKS }
        showLoading()
        loadCatalog()
    }

    private fun showLoading() {
        setContentView(screen("Shared Media", "The One Family bibliotheek", "Laden…"))
    }

    private fun loadCatalog() {
        io.execute {
            try {
                if (!RemoteUsbMusicClient.hasToken(this) &&
                    !RemoteUsbMusicClient.loginForBrowsing(this)
                ) {
                    throw IllegalStateException("Shared Media is tijdelijk niet bereikbaar")
                }
                val loaded = try {
                    RemoteUsbMusicClient.catalog(this)
                } catch (_: RemoteUsbMusicClient.AuthRequired) {
                    RemoteUsbMusicClient.clearToken(this)
                    if (!RemoteUsbMusicClient.loginForBrowsing(this)) {
                        throw IllegalStateException("Shared Media is tijdelijk niet bereikbaar")
                    }
                    RemoteUsbMusicClient.catalog(this)
                }
                runOnUiThread {
                    sticks = loaded
                    if (sticks.isEmpty()) {
                        Toast.makeText(this, "Geen Shared Media gevonden.", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        showSources()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        e.message ?: "Shared Media kon niet worden geladen",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun showSources() {
        currentStick = null
        currentFolder = ""
        val root = screen(
            "Shared Media",
            if (mode == MODE_FOLDER) "Kies een bron en daarna een map" else "Kies een bron en daarna losse nummers",
            null
        )
        val list = root.findViewWithTag<LinearLayout>("content")
        sticks.forEach { stick ->
            val cached = stick.files.count { it.cached }
            list.addView(actionRow(
                title = displayName(stick),
                subtitle = cached.toString() + " van " + stick.totalFiles + " beschikbaar",
                accent = cached > 0
            ) {
                if (cached <= 0) {
                    Toast.makeText(this, "Deze bron wordt nog gesynchroniseerd.", Toast.LENGTH_SHORT).show()
                } else {
                    currentStick = stick
                    showFolder(stick, "")
                }
            })
        }
        val bottom = root.findViewWithTag<LinearLayout>("actions")
        bottom.addView(
            button("Sluiten", false) { finish() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                52.dp
            )
        )
        setContentView(root)
    }

    private fun showFolder(stick: RemoteUsbMusicClient.RemoteStick, prefix: String) {
        currentStick = stick
        currentFolder = prefix.trim('/')
        val folders = childFolders(stick, currentFolder)
        val directFiles = directFiles(stick, currentFolder)
        val title = if (currentFolder.isBlank()) displayName(stick) else currentFolder.substringAfterLast('/')
        val root = screen(
            title,
            if (mode == MODE_FOLDER) "Kies de map die je aan de player wilt toevoegen" else "Tik nummers aan om ze te selecteren",
            null
        )
        val list = root.findViewWithTag<LinearLayout>("content")

        if (mode == MODE_FOLDER) {
            val recursive = filesInFolder(stick, currentFolder)
            list.addView(actionRow(
                title = "＋ Deze map toevoegen",
                subtitle = recursive.size.toString() + " nummer" + if (recursive.size == 1) "" else "s",
                accent = recursive.isNotEmpty()
            ) {
                if (recursive.isEmpty()) {
                    Toast.makeText(this, "Geen afspeelbare nummers in deze map.", Toast.LENGTH_SHORT).show()
                } else {
                    appendFiles(recursive)
                }
            })
        }

        folders.forEach { folder ->
            list.addView(actionRow(
                title = "▣  " + folder.substringAfterLast('/'),
                subtitle = filesInFolder(stick, folder).size.toString() + " nummers",
                accent = true
            ) { showFolder(stick, folder) })
        }

        if (mode == MODE_TRACKS) {
            directFiles.forEach { file ->
                val key = fileKey(file)
                val isSelected = selected.containsKey(key)
                list.addView(actionRow(
                    title = (if (isSelected) "✓  " else "○  ") + cleanTitle(file.displayName),
                    subtitle = if (file.cached) "Shared Media" else "Nog aan het synchroniseren",
                    accent = isSelected
                ) {
                    if (!file.cached) {
                        Toast.makeText(this, "Dit nummer is nog niet beschikbaar.", Toast.LENGTH_SHORT).show()
                    } else {
                        if (selected.containsKey(key)) selected.remove(key) else selected[key] = file
                        showFolder(stick, currentFolder)
                    }
                })
            }
        }

        val bottom = root.findViewWithTag<LinearLayout>("actions")
        bottom.addView(
            button("← Terug", false) {
                if (currentFolder.isBlank()) showSources()
                else showFolder(stick, currentFolder.substringBeforeLast('/', ""))
            },
            LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginEnd = 6.dp }
        )

        if (mode == MODE_TRACKS) {
            bottom.addView(
                button("Toevoegen (" + selected.size + ")", true) {
                    if (selected.isEmpty()) {
                        Toast.makeText(this, "Selecteer eerst één of meer nummers.", Toast.LENGTH_SHORT).show()
                    } else {
                        appendFiles(selected.values.toList())
                    }
                },
                LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginStart = 6.dp }
            )
        } else {
            bottom.addView(
                button("Sluiten", false) { finish() },
                LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginStart = 6.dp }
            )
        }

        setContentView(root)
    }

    private fun appendFiles(files: List<RemoteUsbMusicClient.RemoteFile>) {
        val cached = files.filter { it.cached }.distinctBy { fileKey(it) }
        if (cached.isEmpty()) {
            Toast.makeText(this, "Geen afspeelbare nummers geselecteerd.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val items = cached.map {
                UsbPlaybackService.QueueItem(
                    RemoteUsbMusicClient.streamUrl(this, it),
                    cleanTitle(it.displayName)
                )
            }
            val added = UsbPlaybackService.append(this, items)
            Toast.makeText(
                this,
                added.toString() + " nummer" + if (added == 1) "" else "s" + " toegevoegd aan de player",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        } catch (_: RemoteUsbMusicClient.AuthRequired) {
            showLoading()
            io.execute {
                val ok = runCatching { RemoteUsbMusicClient.loginForBrowsing(this) }.getOrDefault(false)
                runOnUiThread {
                    if (ok) appendFiles(cached)
                    else {
                        Toast.makeText(this, "Shared Media kon niet opnieuw verbinden.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Toevoegen mislukt", Toast.LENGTH_LONG).show()
        }
    }

    private fun childFolders(
        stick: RemoteUsbMusicClient.RemoteStick,
        prefix: String
    ): List<String> {
        val result = linkedSetOf<String>()
        stick.files.forEach { file ->
            val folder = file.folder.replace('\\', '/').trim('/')
            when {
                prefix.isBlank() && folder.isNotBlank() -> result += folder.substringBefore('/')
                prefix.isNotBlank() && folder.startsWith(prefix + "/") -> {
                    val rest = folder.removePrefix(prefix + "/")
                    val child = rest.substringBefore('/')
                    if (child.isNotBlank()) result += prefix + "/" + child
                }
            }
        }
        return result.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun directFiles(
        stick: RemoteUsbMusicClient.RemoteStick,
        prefix: String
    ): List<RemoteUsbMusicClient.RemoteFile> =
        stick.files.filter {
            it.folder.replace('\\', '/').trim('/') == prefix
        }.sortedBy { it.displayName.lowercase(Locale.ROOT) }

    private fun filesInFolder(
        stick: RemoteUsbMusicClient.RemoteStick,
        prefix: String
    ): List<RemoteUsbMusicClient.RemoteFile> =
        stick.files.filter { file ->
            if (!file.cached) false
            else {
                val folder = file.folder.replace('\\', '/').trim('/')
                if (prefix.isBlank()) true
                else folder == prefix || folder.startsWith(prefix + "/")
            }
        }.sortedWith(
            compareBy<RemoteUsbMusicClient.RemoteFile> { it.folder.lowercase(Locale.ROOT) }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )

    private fun fileKey(file: RemoteUsbMusicClient.RemoteFile): String =
        file.deviceId + "\n" + file.stickId + "\n" + file.path

    private fun displayName(stick: RemoteUsbMusicClient.RemoteStick): String {
        val primary =
            stick.deviceName.equals("Surface", true) &&
                stick.stickName.equals("Ruben music", true)
        val fallback =
            stick.deviceName.equals("Ruben", true) &&
                (stick.stickName.equals("Ruben", true) ||
                    stick.stickName.equals("Ruben music", true))
        return if (primary || fallback) "Ruben music"
        else stick.deviceName + " • " + stick.stickName
    }

    private fun cleanTitle(value: String): String =
        value.substringBeforeLast('.', value).trim().ifBlank { value }

    private fun screen(title: String, subtitle: String, message: String?): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 18.dp, 24.dp, 18.dp)
            setBackgroundColor(Color.parseColor("#07101C"))
        }

        root.addView(TextView(this).apply {
            text = "THE ONE FAMILY • SHARED MEDIA"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(Color.parseColor("#D8A451"))
        })
        root.addView(TextView(this).apply {
            text = title
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 4.dp, 0, 2.dp)
        })
        root.addView(TextView(this).apply {
            text = subtitle
            textSize = 16f
            setTextColor(Color.parseColor("#91A4BD"))
            setPadding(0, 0, 0, 12.dp)
        })

        val content = LinearLayout(this).apply {
            tag = "content"
            orientation = LinearLayout.VERTICAL
        }
        if (message != null) {
            content.addView(TextView(this).apply {
                text = message
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(12.dp, 30.dp, 12.dp, 30.dp)
            })
        }

        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            LinearLayout(this).apply {
                tag = "actions"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 10.dp, 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return root
    }

    private fun actionRow(
        title: String,
        subtitle: String,
        accent: Boolean,
        action: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 13.dp, 18.dp, 13.dp)
            setBackgroundResource(if (accent) R.drawable.bg_gold_outline else R.drawable.bg_outline)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = subtitle
            textSize = 14f
            setTextColor(Color.parseColor("#91A4BD"))
            setPadding(0, 3.dp, 0, 0)
        })
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 9.dp }
        return row
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (primary) Color.parseColor("#201505") else Color.WHITE)
            setBackgroundResource(if (primary) R.drawable.bg_amber_button else R.drawable.bg_outline)
            setOnClickListener { action() }
        }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
