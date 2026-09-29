package com.gmailorg.carradio

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Html
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class SupremacyMixesActivity : AppCompatActivity() {
    private data class Mix(val title: String, val url: String, val genre: String)
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var playerBar: LinearLayout
    private lateinit var playerTitle: TextView
    private lateinit var playerPlayPause: Button
    private val playerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val playerRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && ::playerBar.isInitialized) {
                refreshPlayerBar()
                playerHandler.postDelayed(this, 500L)
            }
        }
    }
    private val io = Executors.newSingleThreadExecutor()
    private val favoriteMixUrls = linkedSetOf<String>()
    private var focusTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        focusTitle = intent.getStringExtra("focus_title").orEmpty().trim()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 14.dp, 18.dp, 14.dp)
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            text = "←"
            textSize = 33.0f
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "The One Mixes"
            textSize = 42.0f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.amber))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "NAAR PLAYER  ›"
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#201505"))
            setBackgroundResource(R.drawable.bg_amber_button)
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            setOnClickListener { openPlayer() }
        })
        root.addView(header)

        playerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            setBackgroundResource(R.drawable.bg_player_panel)
        }
        playerTitle = TextView(this).apply {
            text = "Geen muziek actief"
            textSize = 24.0f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            setOnClickListener {
                val active = UsbPlaybackService.snapshot().title
                if (active.isNotBlank()) {
                    startActivity(
                        Intent(this@SupremacyMixesActivity, SupremacyMixesActivity::class.java)
                            .putExtra("focus_title", active)
                    )
                    finish()
                }
            }
        }
        playerBar.addView(
            playerTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        playerBar.addView(Button(this).apply {
            text = "⏮"
            setOnClickListener {
                UsbPlaybackService.previous(this@SupremacyMixesActivity)
                postDelayed({ refreshPlayerBar() }, 80L)
            }
        })
        playerPlayPause = Button(this).apply {
            text = "▶"
            setOnClickListener { UsbPlaybackService.toggle(this@SupremacyMixesActivity) }
        }
        playerBar.addView(playerPlayPause)
        playerBar.addView(Button(this).apply {
            text = "⏭"
            setOnClickListener {
                UsbPlaybackService.next(this@SupremacyMixesActivity)
                postDelayed({ refreshPlayerBar() }, 80L)
            }
        })
        playerBar.addView(Button(this).apply {
            text = "■"
            setOnClickListener {
                UsbPlaybackService.stop(this@SupremacyMixesActivity)
                postDelayed({ refreshPlayerBar() }, 150L)
            }
        })
        root.addView(
            playerBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp
                bottomMargin = 8.dp
            }
        )

        progress = ProgressBar(this)
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })

        status = TextView(this).apply {
            text = "Mixen laden…"
            textSize = 22.5f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            setPadding(0, 8.dp, 0, 10.dp)
        }
        root.addView(status)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadMixes()
    }

    private fun refreshPlayerBar() {
        val state = UsbPlaybackService.snapshot()
        if (!state.hasTrack) {
            playerBar.visibility = View.GONE
            return
        }
        playerBar.visibility = View.VISIBLE
        playerTitle.text = state.title
        playerPlayPause.text = if (state.isPlaying) "⏸" else "▶"
    }

    private fun loadMixes() {
        io.execute {
            val items = linkedMapOf<String, Mix>()
            try { loadOfficial(items) } catch (_: Exception) {}
            try { loadHearThis(items) } catch (_: Exception) {}
            val result = items.values.toList()

            val favorites = try {
                if (!RemoteUsbMusicClient.hasToken(this) &&
                    !RemoteUsbMusicClient.loginForBrowsing(this)
                ) {
                    emptyList()
                } else {
                    RemoteUsbMusicClient.favorites(this)
                }
            } catch (_: Exception) {
                emptyList()
            }
            favoriteMixUrls.clear()
            favorites
                .filter { it.kind.equals("mix", ignoreCase = true) && it.url.isNotBlank() }
                .forEach { favoriteMixUrls += it.url }

            runOnUiThread {
                progress.visibility = View.GONE
                status.text = if (result.isEmpty()) "Geen mixen gevonden." else ""
                result.groupBy { it.genre }.forEach { (genre, mixes) ->
                    addGenreSection(genre, mixes)
                }
            }
        }
    }

    private fun loadOfficial(target: LinkedHashMap<String, Mix>) {
        val html = getText("https://supremacysounds.com/downloads/")
        val rx = Regex("""<a[^>]+href=["']([^"']*files\.supremacysounds\.com[^"']*)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        rx.findAll(html).forEach { m ->
            val url = decode(m.groupValues[1])
            val title = plain(m.groupValues[2])
            if (title.isNotBlank() && url.startsWith("http")) target.putIfAbsent(key(title), Mix(title, url, inferGenre(title)))
        }
    }

    private fun loadHearThis(target: LinkedHashMap<String, Mix>) {
        for (page in 1..5) {
            val json = getText("https://api-v2.hearthis.at/supremacysounds/?type=tracks&count=100&page=$page")
            val array = JSONArray(json)
            if (array.length() == 0) break
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val title = o.optString("title").trim()
                val stream = o.optString("stream_url").trim()
                val genre = normalizeGenre(o.optString("genre"), title)
                if (title.isNotBlank() && stream.startsWith("http")) {
                    val k = key(title)
                    val existing = target[k]
                    if (existing == null || existing.genre == "Overig") target[k] = Mix(title, stream, genre)
                }
            }
            if (array.length() < 100) break
        }
    }

    private fun addGenreSection(genre: String, mixes: List<Mix>) {
        val header = Button(this).apply {
            text = "▶ $genre (${mixes.size})"
            textSize = 27.0f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(context, R.color.amber))
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        }
        val child = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        var populated = false
        fun populate() {
            if (populated) return
            mixes.forEachIndexed { index, _ ->
                addRowTo(child, mixes, index)
            }
            populated = true
        }
        header.setOnClickListener {
            populate()
            val opening = child.visibility != View.VISIBLE
            child.visibility = if (opening) View.VISIBLE else View.GONE
            header.text = (if (opening) "▼ " else "▶ ") + "$genre (${mixes.size})"
        }
        list.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dp
        })
        list.addView(child)

        if (focusTitle.isNotBlank() && mixes.any { it.title.equals(focusTitle, ignoreCase = true) }) {
            populate()
            child.visibility = View.VISIBLE
            header.text = "▼ $genre (${mixes.size})"
        }
    }

    private fun normalizeGenre(raw: String, title: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        return when {
            value.contains("dancehall") -> "Dancehall"
            value.contains("reggae") -> "Reggae"
            value.contains("soca") -> "Soca"
            value.contains("afro") -> "Afrobeats"
            value.contains("hip") || value.contains("rap") -> "Hip-Hop / R&B"
            value.contains("r&b") || value.contains("soul") -> "Hip-Hop / R&B"
            value.contains("pop") -> "Pop"
            value.contains("house") || value.contains("dance") || value.contains("edm") -> "Dance / House"
            value.contains("world") -> inferGenre(title)
            value.isNotBlank() -> raw.trim()
            else -> inferGenre(title)
        }
    }

    private fun inferGenre(title: String): String {
        val t = title.lowercase(Locale.ROOT)
        return when {
            Regex("\\bsoca\\b|trinidad|carnival|power soca|groovy soca").containsMatchIn(t) -> "Soca"
            Regex("dancehall|bashment|jamaica|jamaican").containsMatchIn(t) -> "Dancehall"
            Regex("\\breggae\\b|lovers rock|roots").containsMatchIn(t) -> "Reggae"
            Regex("afrobeats?|afrobeat|amapiano|uganda|ugandan|kenya|kenyan|ghana|nigeria|naija").containsMatchIn(t) -> "Afrobeats"
            Regex("hip.?hop|rap|r&b|rnb|slow jam|soul").containsMatchIn(t) -> "Hip-Hop / R&B"
            Regex("house|edm|dance mix|club bangers").containsMatchIn(t) -> "Dance / House"
            Regex("\\bpop\\b|80s|90s|2000s").containsMatchIn(t) -> "Pop"
            else -> "Overig"
        }
    }

    private fun addRowTo(
        parent: LinearLayout,
        mixes: List<Mix>,
        index: Int
    ) {
        val mix = mixes[index]
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }
        row.addView(TextView(this).apply {
            text = if (mix.title.equals(focusTitle, ignoreCase = true)) "▶ NU • ${mix.title}" else mix.title
            textSize = 25.5f
            setTextColor(
                if (mix.title.equals(focusTitle, ignoreCase = true))
                    android.graphics.Color.parseColor("#D8A451")
                else
                    ContextCompat.getColor(context, R.color.text_main)
            )
            setTypeface(
                typeface,
                if (mix.title.equals(focusTitle, ignoreCase = true)) Typeface.BOLD else Typeface.NORMAL
            )
            maxLines = 3
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val favoriteButton = TextView(this).apply {
            text = if (favoriteMixUrls.contains(mix.url)) "★" else "☆"
            textSize = 31f
            gravity = Gravity.CENTER
            contentDescription = "Favoriet ${mix.title}"
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            setOnClickListener { toggleMixFavorite(mix, this) }
        }
        row.addView(
            favoriteButton,
            LinearLayout.LayoutParams(62.dp, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 10.dp
            }
        )
        row.addView(TextView(this).apply {
            text = "↓"
            textSize = 28f
            gravity = Gravity.CENTER
            contentDescription = "Download ${mix.title}"
            setTextColor(android.graphics.Color.parseColor("#D8A451"))
            setBackgroundResource(R.drawable.bg_gold_outline)
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            setOnClickListener { requestMixDownload(mix) }
        }, LinearLayout.LayoutParams(62.dp, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = 12.dp
        })
        row.addView(TextView(this).apply {
            text = "▶ Afspelen"
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#201505"))
            setBackgroundResource(R.drawable.bg_amber_button)
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            setOnClickListener { play(mixes, index) }
        })
        parent.addView(row)
        parent.addView(
            View(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.dp
            )
        )
    }

    private fun toggleMixFavorite(mix: Mix, button: TextView) {
        val add = !favoriteMixUrls.contains(mix.url)
        button.isEnabled = false
        io.execute {
            val allowed = try {
                CarFamilyAccess.refreshMusicRights(this)
            } catch (_: Exception) {
                false
            }
            val ok = if (allowed) try {
                if (!RemoteUsbMusicClient.hasToken(this) &&
                    !RemoteUsbMusicClient.loginForBrowsing(this)
                ) {
                    throw IllegalStateException("The One Family is niet bereikbaar")
                }
                RemoteUsbMusicClient.setMixFavorite(this, mix.title, mix.url, add)
                true
            } catch (_: Exception) {
                false
            } else false
            runOnUiThread {
                button.isEnabled = true
                if (!allowed) {
                    Toast.makeText(
                        this,
                        "Dit apparaat heeft geen muziekrechten. Geef deze eerst via Main.",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (ok) {
                    if (add) favoriteMixUrls += mix.url else favoriteMixUrls -= mix.url
                    button.text = if (add) "★" else "☆"
                    Toast.makeText(
                        this,
                        if (add) "Toegevoegd aan The One Favorites" else "Verwijderd uit The One Favorites",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "Favoriet opslaan mislukt", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestMixDownload(mix: Mix) {
        io.execute {
            val allowed = try {
                CarFamilyAccess.refreshMusicRights(this)
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (!allowed) {
                    Toast.makeText(
                        this,
                        "Dit apparaat heeft geen muziekrechten. Geef deze eerst via Main.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                showMixDownloadPin(mix)
            }
        }
    }

    private fun showMixDownloadPin(mix: Mix) {
        val input = EditText(this).apply {
            hint = "Pincode"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("The One Mixes")
            .setMessage("Voer je pincode in om deze mix te downloaden.")
            .setView(input)
            .setPositiveButton("Downloaden", null)
            .setNegativeButton("Annuleren", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.isBlank()) return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                io.execute {
                    val valid = try {
                        RemoteUsbMusicClient.login(this, pin)
                    } catch (_: Exception) {
                        false
                    }
                    runOnUiThread {
                        if (valid) {
                            dialog.dismiss()
                            downloadMix(mix)
                        } else {
                            input.text.clear()
                            input.error = "Pincode niet juist"
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun openPlayer() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
        )
        finish()
    }

    private fun downloadMix(mix: Mix) {
        status.text = "Download voorbereiden: ${mix.title}"
        io.execute {
            try {
                val downloadUrl = resolvePlayableUrl(mix.url)
                val safeBase = mix.title
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .trim()
                    .ifBlank { "The One Mix" }
                val fileName =
                    if (safeBase.contains(Regex("""\.[A-Za-z0-9]{2,5}$"""))) safeBase
                    else "$safeBase.mp3"
                val request = DownloadManager.Request(Uri.parse(downloadUrl))
                    .setTitle(mix.title)
                    .setDescription("The One Car • The One Mixes")
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "The One Mixes/$fileName"
                    )
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                runOnUiThread {
                    status.text = ""
                    Toast.makeText(
                        this,
                        "Download gestart: ${mix.title}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Download mislukt"
                    Toast.makeText(
                        this,
                        e.message ?: "Download starten mislukt",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun play(mixes: List<Mix>, selectedIndex: Int) {
        if (mixes.isEmpty() || selectedIndex !in mixes.indices) return

        val selected = mixes[selectedIndex]
        status.text = "Laden: ${selected.title}"

        io.execute {
            try {
                // Los alleen de gekozen mix vooraf op. De overige HTTPS-links
                // kunnen door ExoPlayer zelf worden gevolgd zodra je ⏮/⏭ gebruikt.
                val selectedPlayableUrl = resolvePlayableUrl(selected.url)

                val queue = mixes.mapIndexed { index, mix ->
                    UsbPlaybackService.QueueItem(
                        if (index == selectedIndex) selectedPlayableUrl else mix.url,
                        mix.title
                    )
                }

                runOnUiThread {
                    UsbPlaybackService.play(this, queue, selectedIndex)
                    status.text = "Speelt af: ${selected.title}"
                    refreshPlayerBar()
                    startActivity(
                        Intent(this, CarPlayerActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        }
                    )
                    Toast.makeText(
                        this,
                        "The One Mixes speelt af",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Afspelen mislukt"
                    Toast.makeText(
                        this,
                        e.message ?: "Deze mix kon niet worden afgespeeld",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun resolvePlayableUrl(sourceUrl: String): String {
        var current = sourceUrl
        repeat(6) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "TheOneCar/1.0")
            connection.setRequestProperty("Range", "bytes=0-1")

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) return current
                current = URL(URL(current), location).toString()
            } else {
                val resolved = connection.url.toString()
                connection.disconnect()
                return resolved
            }
        }
        return current
    }

    private fun getText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "TheOne/1.0")
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also { c.disconnect() }
    }

    private fun plain(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun decode(value: String): String =
        value.replace("&amp;", "&").replace("&#038;", "&").replace("&#8211;", "–").trim()

    private fun key(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (CarMediaKeyHandler.handle(this, event)) {
            refreshPlayerBar()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        playerHandler.removeCallbacks(playerRefresh)
        playerRefresh.run()
    }

    override fun onPause() {
        playerHandler.removeCallbacks(playerRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        playerHandler.removeCallbacks(playerRefresh)
        io.shutdownNow()
        super.onDestroy()
    }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
