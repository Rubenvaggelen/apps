package com.theone.remote

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class RemoteActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private val remoteUrl = "https://rustdesk.com/web/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val surfaceId = intent.getStringExtra("surface_id").orEmpty().trim()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#071018"))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.parseColor("#071018"))
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(18), 0)
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(56), dp(48)))

        val title = TextView(this).apply {
            text = "The One Remote"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(top)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host.orEmpty().lowercase()
                if (uri.scheme == "https" && (host == "rustdesk.com" || host.endsWith(".rustdesk.com"))) {
                    return false
                }
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = android.view.View.GONE
                if (surfaceId.isNotBlank()) {
                    prefillSurfaceId(surfaceId)
                }
            }
        }

        webView.loadUrl(remoteUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (surfaceId.isNotBlank()) {
            Toast.makeText(this, "Je Surface-ID wordt automatisch ingevuld.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefillSurfaceId(surfaceId: String) {
        val id = JSONObject.quote(surfaceId)
        val script = """
            (function() {
              try {
                const value = $id;
                const inputs = Array.from(document.querySelectorAll('input'));
                let target = inputs.find(i => /id|remote|device|apparaat/i.test(
                  (i.placeholder || '') + ' ' + (i.name || '') + ' ' + (i.id || '') + ' ' + (i.getAttribute('aria-label') || '')
                ));
                if (!target) target = inputs.find(i => (i.type || 'text') === 'text');
                if (!target) return;
                const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                setter.call(target, value);
                target.dispatchEvent(new Event('input', { bubbles: true }));
                target.dispatchEvent(new Event('change', { bubbles: true }));
                target.focus();
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
