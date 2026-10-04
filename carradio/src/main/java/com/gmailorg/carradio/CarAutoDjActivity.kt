package com.gmailorg.carradio

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream

/** Car uses the same Auto DJ engine as the standalone DJ, with a scoped audio proxy. */
class CarAutoDjActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var tracks = emptyList<UsbPlaybackService.QueueItem>()
    private var initialIndex = 0
    private var initialPosition = 0
    private var initialPlaying = false
    private var handedOff = false
    private var returned = false
    private var initialized = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tracks = UsbPlaybackService.queueSnapshot()
        initialIndex = UsbPlaybackService.currentIndex().coerceAtLeast(0)
        val state = UsbPlaybackService.snapshot()
        initialPosition = state.positionMs
        initialPlaying = state.isPlaying
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(object {
            @JavascriptInterface fun toggleFullscreen() {
                runOnUiThread {
                    WindowCompat.getInsetsController(window, web).hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            @JavascriptInterface fun handoff(): Int {
                // Called only after the initial deck is loaded. Native audio keeps playing while loading.
                val ready = java.util.concurrent.CountDownLatch(1)
                val result = java.util.concurrent.atomic.AtomicInteger(-1)
                runOnUiThread {
                    try {
                        val now = UsbPlaybackService.snapshot()
                        if (now.uri == tracks.getOrNull(initialIndex)?.uri) {
                            result.set(now.positionMs)
                            UsbPlaybackService.pauseDeckA(this@CarAutoDjActivity)
                            UsbPlaybackService.pauseDeckB(this@CarAutoDjActivity)
                            handedOff = true
                        }
                    } finally { ready.countDown() }
                }
                return if (ready.await(3, java.util.concurrent.TimeUnit.SECONDS)) result.get() else -1
            }
            @JavascriptInterface fun returnToPlayer(index: Int, position: Int, playing: Boolean) {
                runOnUiThread {
                    if (returned) return@runOnUiThread
                    returned = true
                    if (handedOff && index in tracks.indices) UsbPlaybackService.playFromPosition(
                        this@CarAutoDjActivity, tracks, index, position.coerceAtLeast(0), playing
                    )
                    finish()
                }
            }
        }, "TheOneNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.host != "appassets.androidplatform.net") return emptyResponse(403)
                if (uri.path?.startsWith("/car-track/") == true) {
                    val index = uri.lastPathSegment?.toIntOrNull()
                    val item = index?.let { tracks.getOrNull(it) } ?: return emptyResponse(404)
                    return audioResponse(item.uri, request)
                }
                return loader.shouldInterceptRequest(uri)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net" || request.url.path?.startsWith("/assets/") != true

            override fun onPageFinished(view: WebView, url: String) {
                if (initialized || !url.startsWith("https://appassets.androidplatform.net/assets/")) return
                initialized = true
                val rows = JSONArray()
                tracks.forEachIndexed { i, track ->
                    rows.put(JSONObject().put("name", track.title + ".mp3").put("type", "audio/mpeg")
                        .put("source", JSONObject().put("kind", "car").put("index", i)))
                }
                val script = """
                  (function(){
                    const carRows=$rows,carIndex=$initialIndex;
                    const originalMediaUrl=mediaUrl;
                    mediaUrl=function(file){return file.source?.kind==="car"?"/car-track/"+file.source.index:originalMediaUrl(file)};
                    window.returnToCar=function(){
                      takeOverAutoDj();
                      const d=decks[state.xf<=0?0:1],index=d.libItem?.file?.source?.index??carIndex;
                      const pos=Math.round(d.getPos()*1000),playing=d.playing;
                      for(const deck of decks)deck.pause();
                      TheOneNative.returnToPlayer(index,pos,playing);
                    };
                    const back=document.createElement("button");back.className="btn";back.textContent="Terug naar Car-player";
                    back.onclick=window.returnToCar;document.body.prepend(back);
                    if(typeof updateBtn!=="undefined"&&updateBtn)updateBtn.hidden=true;
                    openSharedMedia=function(){window.returnToCar()};
                    openLocalMedia=function(){window.returnToCar()};
                    const boot=setInterval(async()=>{
                      if(playlistRestoring)return;clearInterval(boot);
                      autoLoad=false;autoMix=false;lib.splice(0,lib.length);
                      carRows.forEach((file,i)=>lib.push({id:playlistId(),file,name:cleanImportedName(baseName(file)),status:i<carIndex?"played":"queued",sharedName:""}));
                      renderLib();
                      const item=lib[carIndex];
                      if(!item){autoDjStatus("Geen Car-playlist geladen. Voeg muziek toe in de Car-player.");return}
                      autoDjStatus("Auto DJ • huidige nummer voorbereiden");
                      const d=decks[0];await d.load(item.file,item);
                      if(item.status==="error"){autoDjStatus("Nummer kon niet laden; Car-player blijft actief.");return}
                      const milliseconds=TheOneNative.handoff();
                      if(milliseconds<0){autoDjStatus("Car-player is van nummer veranderd. Ga terug en open Auto DJ opnieuw.");return}
                      const pos=milliseconds/1000;
                      d.seek(pos);state.xf=-1;xfader.set(-1,false);applyXfade();d.play();
                      autoMix=true;autoMixArmed=true;autoMixPendingStart=false;autoMixNextDeck=null;
                      updateAutoMixUI();autoDjStatus("Auto DJ • Aan");autoFill();
                    },100);
                  })();
                """.trimIndent()
                view.evaluateJavascript(script, null)
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/car-dj/index.html?app=android&car=true")
        WindowCompat.getInsetsController(window, web).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::web.isInitialized) {
            WindowCompat.getInsetsController(window, web).hide(WindowInsetsCompat.Type.systemBars())
            web.postDelayed({ if (!isFinishing) WindowCompat.getInsetsController(window, web).hide(WindowInsetsCompat.Type.systemBars()) }, 350)
        }
    }

    private fun emptyResponse(code: Int) = WebResourceResponse("text/plain", "UTF-8", code,
        if (code == 404) "Not Found" else "Unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun audioResponse(raw: String, request: WebResourceRequest): WebResourceResponse {
        return try {
            val uri = Uri.parse(raw)
            if (uri.scheme == "https" || uri.scheme == "http") {
                val connection = URL(raw).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.let {
                    connection.setRequestProperty("Range", it.value)
                }
                val code = connection.responseCode
                if (code !in 200..299) { connection.disconnect(); return emptyResponse(502) }
                val headers = mutableMapOf("Cache-Control" to "no-store", "Accept-Ranges" to "bytes")
                for (key in listOf("Content-Length", "Content-Range")) connection.getHeaderField(key)?.let { headers[key] = it }
                val stream = if (request.method == "HEAD") {
                    connection.disconnect(); ByteArrayInputStream(ByteArray(0))
                } else object : java.io.FilterInputStream(connection.inputStream) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
                WebResourceResponse(connection.contentType?.substringBefore(';') ?: "audio/mpeg", null,
                    code, connection.responseMessage ?: "OK", headers, stream)
            } else if (uri.scheme == "content" || uri.scheme == "file") {
                // Decoded local audio is served as a whole stream; do not advertise Range support.
                val stream = if (request.method == "HEAD") ByteArrayInputStream(ByteArray(0))
                    else contentResolver.openInputStream(uri) ?: return emptyResponse(404)
                WebResourceResponse(contentResolver.getType(uri) ?: "audio/mpeg", null, 200, "OK",
                    mapOf("Cache-Control" to "no-store"), stream)
            } else emptyResponse(403)
        } catch (_: Exception) { emptyResponse(502) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (handedOff) web.evaluateJavascript("window.returnToCar&&window.returnToCar()", null)
        else finish()
    }

    override fun onDestroy() {
        if (::web.isInitialized) { web.loadUrl("about:blank"); web.destroy() }
        super.onDestroy()
    }
}
