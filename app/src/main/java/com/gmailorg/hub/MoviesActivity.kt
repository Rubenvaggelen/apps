package com.gmailorg.hub

import android.widget.Button
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.view.Gravity
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MoviesActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var searchButton: View
    private lateinit var resultContainer: LinearLayout
    private lateinit var upcomingContainer: LinearLayout
    private lateinit var musicResultContainer: LinearLayout
    private lateinit var musicPlayerContainer: LinearLayout
    private var activeMusicWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)

        titleInput = findViewById(R.id.movieTitleInput)
        searchButton = findViewById(R.id.movieSearchButton)
        resultContainer = findViewById(R.id.movieResultContainer)
        upcomingContainer = findViewById(R.id.upcomingContainer)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        searchButton.setOnClickListener { searchAll() }
        findViewById<View>(R.id.upcomingLoadButton).setOnClickListener { loadUpcoming() }
        titleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchAll()
                true
            } else {
                false
            }
        }

        musicResultContainer = findViewById(R.id.musicResultContainer)
        musicPlayerContainer = findViewById(R.id.musicPlayerContainer)
    }

    private fun searchAll() {
        val query = titleInput.text.toString().trim()
        if (query.isBlank()) return

        // Eén zoekopdracht voor films/series én muziek.
        resultContainer.removeAllViews()
        musicResultContainer.removeAllViews()
        stopMusicPlayer(hide = true)
        resultContainer.visibility = View.VISIBLE
        musicResultContainer.visibility = View.VISIBLE

        addResultLine("Films & series", bold = true)
        addResultLine("Zoeken naar “$query”…", dim = true)
        addMusicLine("Muziek", dim = false)
        addMusicLine("Zoeken naar “$query”…", dim = true)

        MovieLookup.search(query) { outcome ->
            resultContainer.removeAllViews()
            addResultLine("Films & series", bold = true)
            when (outcome) {
                is MovieLookup.LookupOutcome.Success -> showMovieResult(outcome.result)
                is MovieLookup.LookupOutcome.NotFound ->
                    addResultLine("Geen film of serie gevonden voor “${outcome.query}”.", dim = true)
                is MovieLookup.LookupOutcome.Error ->
                    addResultLine(outcome.message, dim = true)
            }
        }

        MusicLookup.search(query) { outcome ->
            musicResultContainer.removeAllViews()
            addMusicLine("Muziek")
            when (outcome) {
                is MusicLookup.LookupOutcome.Success -> showMusicResults(outcome.results)
                is MusicLookup.LookupOutcome.NotFound ->
                    addMusicLine("Geen muziek gevonden voor “${outcome.query}”.", dim = true)
                is MusicLookup.LookupOutcome.Error ->
                    addMusicLine(outcome.message, dim = true)
            }
        }
    }

    private fun searchMusic(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        musicResultContainer.removeAllViews()
        musicResultContainer.visibility = View.VISIBLE
        addMusicLine("Zoeken naar “$trimmed”…", dim = true)

        MusicLookup.search(trimmed) { outcome ->
            musicResultContainer.removeAllViews()
            when (outcome) {
                is MusicLookup.LookupOutcome.Success -> showMusicResults(outcome.results)
                is MusicLookup.LookupOutcome.NotFound ->
                    addMusicLine("Niets gevonden voor “${outcome.query}”.", dim = true)
                is MusicLookup.LookupOutcome.Error ->
                    addMusicLine(outcome.message, dim = true)
            }
        }
    }

    private fun showMusicResults(results: List<MusicLookup.MusicResult>) {
        results.forEachIndexed { index, result ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ContextCompat.getDrawable(context, R.drawable.bg_music_result)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                setOnClickListener { playMusic(result) }
            }

            val numberView = TextView(this).apply {
                text = "${index + 1}. ${result.title}"
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 14f
                maxLines = 2
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(numberView)

            if (result.channel.isNotBlank()) {
                val channelView = TextView(this).apply {
                    text = result.channel
                    setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                }
                row.addView(channelView)
            }

            val playHint = TextView(this).apply {
                text = "▶  Tik om af te spelen"
                setTextColor(ContextCompat.getColor(context, R.color.amber))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(playHint)
            musicResultContainer.addView(row)
        }
    }

    private fun playMusic(result: MusicLookup.MusicResult) {
        stopMusicPlayer(hide = false)
        musicPlayerContainer.removeAllViews()
        musicPlayerContainer.visibility = View.VISIBLE

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ContextCompat.getDrawable(context, R.drawable.bg_music_player)
        }

        val nowPlaying = TextView(this).apply {
            text = "NU AAN HET SPELEN"
            setTextColor(ContextCompat.getColor(context, R.color.amber))
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(nowPlaying)

        val titleView = TextView(this).apply {
            text = result.title
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            textSize = 16f
            maxLines = 2
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(titleView)

        if (result.channel.isNotBlank()) {
            val channelView = TextView(this).apply {
                text = result.channel
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                textSize = 12f
                setPadding(0, dp(3), 0, dp(8))
            }
            card.addView(channelView)
        }

        val player = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(200)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        activeMusicWebView = player

        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
              <style>
                html,body{margin:0;padding:0;background:#171A24;width:100%;height:100%;overflow:hidden;}
                iframe{border:0;width:100%;height:100%;}
              </style>
            </head>
            <body>
              <iframe
                src="https://www.youtube.com/embed/${result.videoId}?autoplay=1&playsinline=1&controls=1&rel=0"
                title="YouTube player"
                allow="autoplay; encrypted-media; picture-in-picture"
                allowfullscreen>
              </iframe>
            </body>
            </html>
        """.trimIndent()

        player.loadDataWithBaseURL(
            "https://www.youtube.com/",
            html,
            "text/html",
            "UTF-8",
            null
        )
        card.addView(player)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }

        val youtubeButton = Button(this).apply {
            text = "YouTube"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { openVideo(result.videoId) }
        }
        buttonRow.addView(youtubeButton)

        val stopButton = Button(this).apply {
            text = "Stop"
            textSize = 12f
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.on_amber))
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.amber)
            setOnClickListener { stopMusicPlayer(hide = true) }
        }
        buttonRow.addView(stopButton)

        card.addView(buttonRow)
        musicPlayerContainer.addView(card)
    }

    private fun stopMusicPlayer(hide: Boolean) {
        activeMusicWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        activeMusicWebView = null
        musicPlayerContainer.removeAllViews()
        if (hide) musicPlayerContainer.visibility = View.GONE
    }

    override fun onDestroy() {
        if (::musicPlayerContainer.isInitialized) {
            stopMusicPlayer(hide = true)
        }
        super.onDestroy()
    }

    private fun openVideo(videoId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        startActivity(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun addMusicLine(text: String, dim: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, if (dim) R.color.text_dim else R.color.text_main))
            textSize = 13f
            setPadding(0, 4, 0, 4)
        }
        musicResultContainer.addView(view)
    }

    private fun loadUpcoming() {
        upcomingContainer.removeAllViews()
        addUpcomingLine("Bezig met laden...", dim = true)

        MovieLookup.fetchUpcomingMarvelAndDc { releases, error ->
            upcomingContainer.removeAllViews()
            if (error != null) {
                addUpcomingLine(error, dim = true)
                return@fetchUpcomingMarvelAndDc
            }
            if (releases.isEmpty()) {
                addUpcomingLine("Geen aankomende releases gevonden.", dim = true)
                return@fetchUpcomingMarvelAndDc
            }
            releases.forEach { release ->
                val dateText = release.releaseDate?.let { formatDutchDate(it) } ?: "datum onbekend"
                addUpcomingLine("${release.title} — ${release.studio}", bold = true)
                addUpcomingLine(dateText, dim = true)
            }
        }
    }

    private fun formatDutchDate(isoDate: String): String {
        return try {
            val parts = isoDate.split("-")
            val months = listOf(
                "januari", "februari", "maart", "april", "mei", "juni",
                "juli", "augustus", "september", "oktober", "november", "december"
            )
            val day = parts[2].toInt()
            val month = months[parts[1].toInt() - 1]
            val year = parts[0]
            "$day $month $year"
        } catch (e: Exception) {
            isoDate
        }
    }

    private fun addUpcomingLine(text: String, bold: Boolean = false, dim: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, if (dim) R.color.text_dim else R.color.text_main))
            textSize = if (bold) 15f else 13f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, if (dim) 10 else 2)
        }
        upcomingContainer.addView(view)
    }

    private fun searchMovie() {
        val query = titleInput.text.toString().trim()
        if (query.isBlank()) return

        resultContainer.removeAllViews()
        resultContainer.visibility = View.VISIBLE
        addResultLine("Zoeken naar “$query”…", dim = true)

        MovieLookup.search(query) { outcome ->
            resultContainer.removeAllViews()
            when (outcome) {
                is MovieLookup.LookupOutcome.Success -> showMovieResult(outcome.result)
                is MovieLookup.LookupOutcome.NotFound ->
                    addResultLine("Geen film of serie gevonden voor “${outcome.query}”.", dim = true)
                is MovieLookup.LookupOutcome.Error ->
                    addResultLine(outcome.message, dim = true)
            }
        }
    }

    private fun showMovieResult(result: MovieLookup.MovieResult) {
        val type = if (result.isSeries) "Serie" else "Film"
        addResultLine(type, dim = true)
        val titleLine = if (result.year != null) "${result.title} (${result.year})" else result.title
        addResultLine(titleLine, bold = true)

        if (result.availableOn.isEmpty() && result.rentOrBuyOn.isEmpty()) {
            addResultLine("Niet gevonden bij een streamingdienst in Nederland.", dim = true)
            return
        }
        if (result.availableOn.isNotEmpty()) {
            addResultLine("Kijken (abonnement/gratis): ${result.availableOn.joinToString(", ")}")
        }
        if (result.rentOrBuyOn.isNotEmpty()) {
            addResultLine("Huren of kopen: ${result.rentOrBuyOn.joinToString(", ")}")
        }
    }

    private fun addResultLine(text: String, bold: Boolean = false, dim: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, if (dim) R.color.text_dim else R.color.text_main))
            textSize = if (bold) 17f else 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }
        resultContainer.addView(view)
    }
}
