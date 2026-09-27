package com.gmailorg.hub

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MoviesActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var searchButton: View
    private lateinit var resultContainer: LinearLayout
    private lateinit var upcomingContainer: LinearLayout
    private lateinit var musicResultContainer: LinearLayout
    private lateinit var musicPlayerCard: View
    private lateinit var musicNowPlaying: TextView
    private lateinit var musicPlaybackState: TextView
    private lateinit var musicWebPlayer: WebView
    private var youtubeActive = false
    private var youtubePlaying = false
    private val remoteMusicIo = Executors.newSingleThreadExecutor()

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
        musicPlaybackState = findViewById(R.id.musicPlaybackState)
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
            } else if (youtubeActive) {
                musicWebPlayer.evaluateJavascript("window.theOneToggle && window.theOneToggle();", null)
                youtubePlaying = !youtubePlaying
                musicPlaybackState.text = if (youtubePlaying) "Speelt af" else "Gepauzeerd"
            }
        }
        findViewById<View>(R.id.musicStopButton).setOnClickListener {
            if (SupremacyPlaybackService.isActive(this)) {
                sendSupremacyAction(SupremacyPlaybackService.ACTION_STOP)
            } else if (youtubeActive) {
                musicWebPlayer.evaluateJavascript("window.theOneStop && window.theOneStop();", null)
            }
            youtubeActive = false
            youtubePlaying = false
            musicNowPlaying.text = "Geen muziek actief"
            musicPlaybackState.text = "Gestopt"
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

        findViewById<View>(R.id.remoteUsbMusicButton).setOnClickListener {
            openRemoteUsbMusic()
        }

    }

    override fun onResume() {
        super.onResume()
        refreshCompactPlayer()
    }

    private fun refreshCompactPlayer() {
        if (SupremacyPlaybackService.isActive(this)) {
            musicNowPlaying.text =
                SupremacyPlaybackService.currentTitle(this) + "  •  " +
                    SupremacyPlaybackService.currentSource(this)
            musicPlaybackState.text =
                if (SupremacyPlaybackService.isPlaying(this)) "Speelt af" else "Gepauzeerd"
            return
        }

        if (youtubeActive) {
            musicPlaybackState.text = if (youtubePlaying) "Speelt af" else "Gepauzeerd"
        } else {
            musicNowPlaying.text = "Geen muziek actief"
            musicPlaybackState.text = "Gestopt"
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

    private fun openRemoteUsbMusic() {
        if (RemoteUsbMusicClient.hasToken(this)) {
            loadRemoteUsbCatalog()
        } else {
            showRemoteUsbPinDialog()
        }
    }

    private fun showRemoteUsbPinDialog() {
        val input = EditText(this).apply {
            hint = "Pincode"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = android.view.Gravity.CENTER
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("USB thuis")
            .setMessage("Voer de pincode in voor je USB-muziek.")
            .setView(input)
            .setPositiveButton("Openen", null)
            .setNegativeButton("Annuleren", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.isBlank()) return@setOnClickListener

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                remoteMusicIo.execute {
                    val ok = try {
                        RemoteUsbMusicClient.login(this, pin)
                    } catch (_: Exception) {
                        false
                    }

                    runOnUiThread {
                        if (ok) {
                            dialog.dismiss()
                            loadRemoteUsbCatalog()
                        } else {
                            input.text.clear()
                            input.error = "Pincode niet juist of server niet bereikbaar"
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun loadRemoteUsbCatalog() {
        Toast.makeText(this, "USB thuis laden…", Toast.LENGTH_SHORT).show()
        remoteMusicIo.execute {
            try {
                val sticks = RemoteUsbMusicClient.catalog(this)
                runOnUiThread {
                    if (sticks.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("USB thuis")
                            .setMessage("Nog geen gesynchroniseerde USB-muziek gevonden.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        showRemoteStickDialog(sticks)
                    }
                }
            } catch (_: RemoteUsbMusicClient.AuthRequired) {
                runOnUiThread { showRemoteUsbPinDialog() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        e.message ?: "USB thuis kon niet worden geladen",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showRemoteStickDialog(sticks: List<RemoteUsbMusicClient.RemoteStick>) {
        val labels = sticks.map { it.deviceName + " • " + it.stickName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("USB thuis")
            .setItems(labels) { _, which ->
                showRemoteFolderDialog(sticks[which])
            }
            .setNegativeButton("Sluiten", null)
            .show()
    }

    private fun showRemoteFolderDialog(stick: RemoteUsbMusicClient.RemoteStick) {
        val groups = stick.files
            .groupBy { it.folder.ifBlank { "Hoofdmap" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)

        val folders = groups.keys.toList()
        AlertDialog.Builder(this)
            .setTitle(stick.deviceName + " • " + stick.stickName)
            .setItems(folders.toTypedArray()) { _, which ->
                val folder = folders[which]
                val files = groups[folder].orEmpty()
                    .sortedBy { it.name.lowercase() }
                showRemoteTrackDialog(stick, folder, files)
            }
            .setNegativeButton("Terug") { _, _ -> showRemoteStickDialog(listOf(stick)) }
            .show()
    }

    private fun showRemoteTrackDialog(
        stick: RemoteUsbMusicClient.RemoteStick,
        folder: String,
        files: List<RemoteUsbMusicClient.RemoteFile>
    ) {
        val trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 8, 18, 8)
        }

        files.forEachIndexed { index, file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(8, 6, 8, 6)
                setOnClickListener { playRemoteUsbFolder(stick, files, index) }
            }

            val number = TextView(this).apply {
                text = (index + 1).toString().padStart(2, '0')
                textSize = 12f
                setTextColor(Color.parseColor("#20B8FF"))
                gravity = android.view.Gravity.CENTER
            }
            row.addView(
                number,
                LinearLayout.LayoutParams(52, LinearLayout.LayoutParams.WRAP_CONTENT)
            )

            val title = TextView(this).apply {
                text = cleanUsbTrackTitle(file.name)
                textSize = 16f
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(10, 16, 10, 16)
            }
            row.addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            val play = TextView(this).apply {
                text = "▶"
                textSize = 19f
                setTextColor(Color.parseColor("#20B8FF"))
                gravity = android.view.Gravity.CENTER
                setPadding(18, 12, 18, 12)
                setOnClickListener { playRemoteUsbFolder(stick, files, index) }
            }
            row.addView(
                play,
                LinearLayout.LayoutParams(64, LinearLayout.LayoutParams.WRAP_CONTENT)
            )

            trackList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            if (index < files.lastIndex) {
                trackList.addView(
                    View(this).apply { setBackgroundColor(Color.parseColor("#263241")) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                )
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(trackList)
        }

        AlertDialog.Builder(this)
            .setTitle(folder)
            .setView(scroll)
            .setNegativeButton("Terug") { _, _ -> showRemoteFolderDialog(stick) }
            .show()
    }

    private fun cleanUsbTrackTitle(raw: String): String =
        raw
            .replace(Regex("\\.(mp3|wma|m4a|aac|flac|ogg|wav)$", RegexOption.IGNORE_CASE), "")
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun playRemoteUsbFolder(
        stick: RemoteUsbMusicClient.RemoteStick,
        files: List<RemoteUsbMusicClient.RemoteFile>,
        index: Int
    ) {
        try {
            musicWebPlayer.evaluateJavascript("window.theOneStop && window.theOneStop();", null)
            youtubeActive = false
            youtubePlaying = false

            val urls = ArrayList(files.map { RemoteUsbMusicClient.streamUrl(this, it) })
            val titles = ArrayList(files.map { it.name })

            ContextCompat.startForegroundService(
                this,
                Intent(this, SupremacyPlaybackService::class.java).apply {
                    action = SupremacyPlaybackService.ACTION_PLAY
                    putStringArrayListExtra(SupremacyPlaybackService.EXTRA_QUEUE_URLS, urls)
                    putStringArrayListExtra(SupremacyPlaybackService.EXTRA_QUEUE_TITLES, titles)
                    putExtra(SupremacyPlaybackService.EXTRA_INDEX, index)
                    putExtra(
                        SupremacyPlaybackService.EXTRA_SOURCE,
                        "USB thuis • " + stick.deviceName
                    )
                }
            )

            musicNowPlaying.text = files[index].name + "  •  USB thuis"
            musicPlaybackState.text = "Laden…"
            musicNowPlaying.postDelayed({ refreshCompactPlayer() }, 500)
        } catch (_: RemoteUsbMusicClient.AuthRequired) {
            RemoteUsbMusicClient.clearToken(this)
            showRemoteUsbPinDialog()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Afspelen mislukt", Toast.LENGTH_LONG).show()
        }
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
        if (SupremacyPlaybackService.isActive(this)) {
            sendSupremacyAction(SupremacyPlaybackService.ACTION_STOP)
        }
        youtubeActive = true
        youtubePlaying = true
        musicNowPlaying.text = result.title +
            if (result.channel.isNotBlank()) "  •  ${result.channel}" else ""
        musicPlaybackState.text = "Speelt af"

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
        remoteMusicIo.shutdownNow()
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
