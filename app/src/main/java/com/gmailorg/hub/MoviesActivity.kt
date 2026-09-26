package com.gmailorg.hub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MoviesActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var searchButton: View
    private lateinit var resultContainer: LinearLayout
    private lateinit var upcomingContainer: LinearLayout
    private lateinit var musicResultContainer: LinearLayout
    private lateinit var musicPlayerCard: View
    private lateinit var musicNowPlaying: TextView
    private lateinit var musicWebPlayer: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)
        MenuButtonHelper.attach(this)

        titleInput = findViewById(R.id.movieTitleInput)
        searchButton = findViewById(R.id.movieSearchButton)
        resultContainer = findViewById(R.id.movieResultContainer)
        upcomingContainer = findViewById(R.id.upcomingContainer)

        findViewById<View>(R.id.backButton).setOnClickListener { MenuButtonHelper.goToMenu(this) }
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
        musicPlayerCard = findViewById(R.id.musicPlayerCard)
        musicNowPlaying = findViewById(R.id.musicNowPlaying)
        musicWebPlayer = findViewById(R.id.musicWebPlayer)
        configureMusicPlayer()
        findViewById<View>(R.id.musicPreviousButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_PREVIOUS)
            } else {
                musicWebPlayer.evaluateJavascript("window.theOnePrevious && window.theOnePrevious();", null)
            }
        }
        findViewById<View>(R.id.musicPlayPauseButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_TOGGLE)
            } else {
                musicWebPlayer.evaluateJavascript("window.theOneToggle && window.theOneToggle();", null)
            }
        }
        findViewById<View>(R.id.musicStopButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_STOP)
            } else {
                musicWebPlayer.evaluateJavascript("window.theOneStop && window.theOneStop();", null)
            }
            musicNowPlaying.text = "Geen muziek actief"
        }
        findViewById<View>(R.id.musicNextButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_NEXT)
            } else {
                musicWebPlayer.evaluateJavascript("window.theOneNext && window.theOneNext();", null)
            }
        }

        findViewById<View>(R.id.supremacyMixesButton).setOnClickListener {
            startActivity(Intent(this, SupremacyMixesActivity::class.java))
        }

    }

    override fun onResume() {
        super.onResume()
        refreshCompactPlayer()
    }

    private fun refreshCompactPlayer() {
        if (SupremacyPlaybackService.isActive(this)) {
            musicNowPlaying.text =
                SupremacyPlaybackService.currentTitle(this) + "  •  Supremacy"
        }
    }

    private fun sendSupremacyAction(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, SupremacyPlaybackService::class.java).apply {
                this.action = action
            }
        )
        musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 150)
    }

    private fun searchAll() {
        val query = titleInput.text.toString().trim()
        if (query.isBlank()) return

        // Eén zoekopdracht voor films/series én muziek.
        resultContainer.removeAllViews()
        musicResultContainer.removeAllViews()
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
                setPadding(12, 10, 12, 10)
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                setOnClickListener { playVideo(result, results.drop(index).map { it.videoId }) }
            }
            val titleView = TextView(this).apply {
                text = result.title
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 14f
                maxLines = 2
            }
            row.addView(titleView)
            if (result.channel.isNotBlank()) {
                val channelView = TextView(this).apply {
                    text = result.channel
                    setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                    textSize = 12f
                    setPadding(0, 2, 0, 0)
                }
                row.addView(channelView)
            }
            musicResultContainer.addView(row)
        }
    }

    private fun configureMusicPlayer() {
        musicWebPlayer.settings.javaScriptEnabled = true
        musicWebPlayer.settings.domStorageEnabled = true
        musicWebPlayer.settings.mediaPlaybackRequiresUserGesture = false
        musicWebPlayer.webViewClient = WebViewClient()
        musicWebPlayer.webChromeClient = WebChromeClient()
    }

    private fun playVideo(result: MusicLookup.MusicResult, queue: List<String>) {
        musicNowPlaying.text = result.title +
            if (result.channel.isNotBlank()) "  •  ${result.channel}" else ""

        val cleanQueue = queue
            .map { id -> id.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
            .filter { it.isNotBlank() }
            .take(20)

        val queueJson = org.json.JSONArray(cleanQueue).toString()
        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                html,body,#player{width:100%;height:100%;margin:0;background:#05070B;overflow:hidden}
              </style>
            </head>
            <body>
              <div id="player"></div>
              <script>
                const queue = $queueJson;
                let player;
                function onYouTubeIframeAPIReady() {
                  if (!queue.length) return;
                  player = new YT.Player('player', {
                    width: '100%',
                    height: '100%',
                    playerVars: { autoplay: 1, playsinline: 1, rel: 0, modestbranding: 1 },
                    events: {
                      onReady: e => e.target.loadPlaylist({
                        playlist: queue,
                        index: 0,
                        startSeconds: 0
                      })
                    }
                  });
                  window.theOneToggle = () => {
                    if (!player || !player.getPlayerState) return;
                    const state = player.getPlayerState();
                    if (state === YT.PlayerState.PLAYING) player.pauseVideo();
                    else player.playVideo();
                  };
                  window.theOnePrevious = () => {
                    if (player && player.previousVideo) player.previousVideo();
                  };
                  window.theOneNext = () => {
                    if (player && player.nextVideo) player.nextVideo();
                  };
                  window.theOneStop = () => {
                    if (player && player.stopVideo) player.stopVideo();
                  };
                }
                const api = document.createElement('script');
                api.src = 'https://www.youtube.com/iframe_api';
                document.head.appendChild(api);
              </script>
            </body>
            </html>
        """.trimIndent()

        musicWebPlayer.loadDataWithBaseURL(
            "https://www.youtube.com",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }
    @Deprecated("Back keeps the player alive and returns to The One menu")
    override fun onBackPressed() {
        MenuButtonHelper.goToMenu(this)
    }

    override fun onDestroy() {
        if (isFinishing && ::musicWebPlayer.isInitialized) {
            musicWebPlayer.stopLoading()
            musicWebPlayer.destroy()
        }
        super.onDestroy()
    }

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
