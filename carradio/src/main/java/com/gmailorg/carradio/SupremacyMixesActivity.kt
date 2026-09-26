package com.gmailorg.carradio

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 14.dp, 18.dp, 14.dp)
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Supremacy mixen"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.amber))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        progress = ProgressBar(this)
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })

        status = TextView(this).apply {
            text = "Mixen laden…"
            textSize = 15f
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

    private fun loadMixes() {
        io.execute {
            val items = linkedMapOf<String, Mix>()
            try { loadOfficial(items) } catch (_: Exception) {}
            try { loadHearThis(items) } catch (_: Exception) {}
            val result = items.values.toList()
            runOnUiThread {
                progress.visibility = View.GONE
                status.text = if (result.isEmpty()) "Geen mixen gevonden." else ""
                result.groupBy { it.genre }.forEach { (genre, mixes) ->
                    addGenreHeader(genre)
                    mixes.forEach { addRow(it) }
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
                if (title.isNotBlank() && stream.startsWith("http")) target.putIfAbsent(key(title), Mix(title, stream))
            }
            if (array.length() < 100) break
        }
    }

    private fun addGenreHeader(genre: String) {
        list.addView(TextView(this).apply {
            text = genre
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.amber))
            setPadding(4.dp, 18.dp, 4.dp, 8.dp)
        })
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

    private fun addRow(mix: Mix) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        }
        row.addView(TextView(this).apply {
            text = mix.title
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            maxLines = 3
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "▶ Afspelen"
            setTextColor(ContextCompat.getColor(context, R.color.on_amber))
            setBackgroundColor(ContextCompat.getColor(context, R.color.amber))
            setOnClickListener { play(mix) }
        })
        list.addView(row)
        list.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.surface)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp))
    }

    private fun play(mix: Mix) {
        ContextCompat.startForegroundService(this, Intent(this, SupremacyPlaybackService::class.java).apply {
            action = SupremacyPlaybackService.ACTION_PLAY
            putExtra(SupremacyPlaybackService.EXTRA_TITLE, mix.title)
            putExtra(SupremacyPlaybackService.EXTRA_URL, mix.url)
        })
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

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
