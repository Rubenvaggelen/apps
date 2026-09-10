from pathlib import Path
import re

root = Path('.')
kt_path = root / 'app/src/main/java/com/gmailorg/hub/MoviesActivity.kt'
xml_path = root / 'app/src/main/res/layout/activity_movies.xml'
drawable_dir = root / 'app/src/main/res/drawable'

if not kt_path.exists() or not xml_path.exists():
    raise SystemExit('❌ Voer dit uit vanuit de root van je GitHub-project (waar app/ staat).')

kt = kt_path.read_text(encoding='utf-8')
xml = xml_path.read_text(encoding='utf-8')

# Imports
imports = [
    'import android.graphics.Color',
    'import android.view.Gravity',
    'import android.webkit.WebChromeClient',
    'import android.webkit.WebView',
    'import android.widget.Button',
]
for imp in imports:
    if imp not in kt:
        # insert after package/import block start, before first existing android import if possible
        m = re.search(r'(?m)^import android\.', kt)
        if m:
            kt = kt[:m.start()] + imp + '\n' + kt[m.start():]
        else:
            kt = kt.replace('package com.gmailorg.hub\n', 'package com.gmailorg.hub\n\n' + imp + '\n', 1)

# Fields
if 'private lateinit var musicPlayerContainer: LinearLayout' not in kt:
    kt = kt.replace(
        '    private lateinit var musicResultContainer: LinearLayout\n',
        '    private lateinit var musicResultContainer: LinearLayout\n'
        '    private lateinit var musicPlayerContainer: LinearLayout\n'
        '    private var activeMusicWebView: WebView? = null\n',
        1,
    )

# onCreate binding
if 'musicPlayerContainer = findViewById(R.id.musicPlayerContainer)' not in kt:
    anchor = '        musicResultContainer = findViewById(R.id.musicResultContainer)'
    if anchor not in kt:
        raise SystemExit('❌ musicResultContainer-binding niet gevonden in MoviesActivity.kt')
    kt = kt.replace(anchor, anchor + '\n        musicPlayerContainer = findViewById(R.id.musicPlayerContainer)', 1)

# New search stops existing player
search_anchor = '        musicResultContainer.removeAllViews()\n'
if 'stopMusicPlayer(hide = true)' not in kt:
    pos = kt.find('    private fun searchAll()')
    if pos != -1:
        sub = kt[pos:]
        idx = sub.find(search_anchor)
        if idx != -1:
            abs_idx = pos + idx + len(search_anchor)
            kt = kt[:abs_idx] + '        stopMusicPlayer(hide = true)\n' + kt[abs_idx:]

# Replace music result renderer and openVideo section safely.
pattern = re.compile(
    r'    private fun showMusicResults\(results: List<MusicLookup\.MusicResult>\) \{.*?\n    private fun openVideo\(videoId: String\) \{.*?\n    \}\n',
    re.S,
)

replacement = r'''    private fun showMusicResults(results: List<MusicLookup.MusicResult>) {
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
'''

kt2, n = pattern.subn(replacement, kt, count=1)
if n == 0:
    if 'private fun playMusic(result: MusicLookup.MusicResult)' not in kt:
        raise SystemExit('❌ Kon showMusicResults/openVideo niet herkennen. Stuur MoviesActivity.kt als die inmiddels sterk gewijzigd is.')
else:
    kt = kt2

# Insert player container in layout before result container
if 'android:id="@+id/musicPlayerContainer"' not in xml:
    marker = '''            <LinearLayout
                android:id="@+id/musicResultContainer"'''
    if marker not in xml:
        raise SystemExit('❌ musicResultContainer niet gevonden in activity_movies.xml')
    player_xml = '''            <LinearLayout
                android:id="@+id/musicPlayerContainer"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingHorizontal="16dp"
                android:paddingBottom="12dp"
                android:visibility="gone" />

'''
    xml = xml.replace(marker, player_xml + marker, 1)

kt_path.write_text(kt, encoding='utf-8')
xml_path.write_text(xml, encoding='utf-8')

drawable_dir.mkdir(parents=True, exist_ok=True)
(drawable_dir / 'bg_music_player.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface_raised" />
    <stroke android:width="1dp" android:color="@color/amber" />
    <corners android:radius="18dp" />
    <padding android:left="2dp" android:top="2dp" android:right="2dp" android:bottom="2dp" />
</shape>
''', encoding='utf-8')

(drawable_dir / 'bg_music_result.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/surface_raised" />
            <stroke android:width="1dp" android:color="@color/amber" />
            <corners android:radius="14dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/surface" />
            <stroke android:width="1dp" android:color="@color/line" />
            <corners android:radius="14dp" />
        </shape>
    </item>
</selector>
''', encoding='utf-8')

print('✅ Compacte ingebouwde YouTube-muziekspeler toegevoegd.')
print('✅ Tik op een muziekresultaat: speler verschijnt boven de lijst.')
print('✅ De 10 zoekresultaten blijven zichtbaar.')
print('✅ Stop-knop + YouTube-fallback toegevoegd.')
