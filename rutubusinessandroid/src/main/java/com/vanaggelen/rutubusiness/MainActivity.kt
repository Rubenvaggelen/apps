package com.vanaggelen.rutubusiness

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(9, 8, 7))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            webViewClient = WebViewClient()
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/business.html")
    }

    @Deprecated("Deprecated in Android SDK")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function(){const m=document.getElementById('orderModal');if(m&&m.classList.contains('open')){closeOrderModal();return 'closed'}return 'none'})()"
        ) { result ->
            if (result != "\"closed\"") {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }
}
