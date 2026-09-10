from pathlib import Path
import re

root = Path('.')
kt_path = root / 'app/src/main/java/com/gmailorg/hub/MoviesActivity.kt'
if not kt_path.exists():
    raise SystemExit('❌ MoviesActivity.kt niet gevonden. Voer dit uit vanuit /workspaces/apps.')

kt = kt_path.read_text(encoding='utf-8')

# Required imports
imports = [
    'import android.os.Build',
    'import android.webkit.CookieManager',
    'import android.webkit.WebViewClient',
]
for imp in imports:
    if imp not in kt:
        m = re.search(r'(?m)^import ', kt)
        if m:
            kt = kt[:m.start()] + imp + '\n' + kt[m.start():]
        else:
            kt = kt.replace('package com.gmailorg.hub\n', 'package com.gmailorg.hub\n\n' + imp + '\n', 1)

# Replace playMusic only; preserve surrounding app code.
pattern = re.compile(
    r'    private fun playMusic\(result: MusicLookup\.MusicResult\) \{.*?\n    \}\n\n    private fun stopMusicPlayer\(hide: Boolean\)',
    re.S,
)

replacement = r'''    private fun playMusic(result: MusicLookup.MusicResult) {
        stopMusicPlayer(hide = false)
        musicPlayerContainer.removeAllViews()
        musicPlayerContainer.visibility = View.VISIBLE

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ContextCompat.getDrawable(context, R.drawable.bg_music_player)
        }

        val nowPlaying = TextView(this).apply {
            text = "MUZIEKSPELER"
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

        val statusView = TextView(this).apply {
            text = "Player laden…"
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        }
        card.addView(statusView)

        val player = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)
            ).apply {
                topMargin = dp(4)
            }
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    statusView.text = "Klaar — tik op Afspelen"
                    view?.evaluateJavascript(
                        "if (typeof nativePlay === 'function') { nativePlay(); }",
                        null
                    )
                }
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
        }
        activeMusicWebView = player

        val safeVideoId = result.videoId.replace(Regex("[^A-Za-z0-9_-]"), "")
        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
              <style>
                html,body,#player { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
              </style>
            </head>
            <body>
              <div id="player"></div>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                var player = null;
                var pendingPlay = true;

                function onYouTubeIframeAPIReady() {
                  player = new YT.Player('player', {
                    width: '100%',
                    height: '100%',
                    videoId: '$safeVideoId',
                    playerVars: {
                      playsinline: 1,
                      controls: 1,
                      rel: 0,
                      autoplay: 0,
                      enablejsapi: 1,
                      origin: 'https://www.youtube.com'
                    },
                    events: {
                      onReady: function(event) {
                        if (pendingPlay) event.target.playVideo();
                      }
                    }
                  });
                }

                function nativePlay() {
                  pendingPlay = true;
                  if (player && typeof player.playVideo === 'function') {
                    player.playVideo();
                  }
                }

                function nativePause() {
                  pendingPlay = false;
                  if (player && typeof player.pauseVideo === 'function') {
                    player.pauseVideo();
                  }
                }
              </script>
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
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        val playButton = Button(this).apply {
            text = "▶ Afspelen"
            textSize = 12f
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.on_amber))
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.amber)
            setOnClickListener {
                statusView.text = "Afspelen…"
                activeMusicWebView?.evaluateJavascript(
                    "if (typeof nativePlay === 'function') { nativePlay(); }",
                    null
                )
            }
        }
        buttonRow.addView(playButton)

        val pauseButton = Button(this).apply {
            text = "⏸ Pauze"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                statusView.text = "Gepauzeerd"
                activeMusicWebView?.evaluateJavascript(
                    "if (typeof nativePause === 'function') { nativePause(); }",
                    null
                )
            }
        }
        buttonRow.addView(pauseButton)

        val stopButton = Button(this).apply {
            text = "■ Stop"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { stopMusicPlayer(hide = true) }
        }
        buttonRow.addView(stopButton)

        val youtubeButton = Button(this).apply {
            text = "YouTube"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { openVideo(result.videoId) }
        }
        buttonRow.addView(youtubeButton)

        card.addView(buttonRow)
        musicPlayerContainer.addView(card)
    }

    private fun stopMusicPlayer(hide: Boolean)'''

kt2, n = pattern.subn(replacement, kt, count=1)
if n == 0:
    raise SystemExit('❌ De bestaande playMusic-functie kon niet worden gevonden. De app-code is waarschijnlijk gewijzigd.')

kt_path.write_text(kt2, encoding='utf-8')
print('✅ YouTube-player gerepareerd')
print('✅ Echte IFrame Player API gebruikt')
print('✅ Afspelen / Pauze / Stop toegevoegd')
print('✅ Player blijft zichtbaar en compact')
