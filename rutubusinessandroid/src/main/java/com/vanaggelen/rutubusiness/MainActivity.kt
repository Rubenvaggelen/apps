package com.vanaggelen.rutubusiness

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var businessUnlocked = false
    private var lastUpdateCheck = 0L

    private fun checkForBusinessUpdate(manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastUpdateCheck < 6 * 60 * 60 * 1000L) return
        lastUpdateCheck = now
        RutuBusinessUpdateChecker.checkForUpdate(this, manual)
    }

    override fun onResume() {
        super.onResume()
        if (businessUnlocked) checkForBusinessUpdate()
    }

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
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.url.scheme == "rutuupdate" && request.url.host == "check") {
                        if (view.url == "file:///android_asset/business.html") checkForBusinessUpdate(manual = true)
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.clearHistory()
                    view.scrollTo(0, 0)
                }
            }
        }
        requestBusinessCode()
    }

    private fun requestBusinessCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Bedrijfscode"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rutu BBQ Bedrijf")
            .setMessage("Voer de bedrijfscode in.")
            .setView(input)
            .setCancelable(false)
            .setNegativeButton("Sluiten") { _, _ -> finish() }
            .setPositiveButton("Openen", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() == "170250") {
                    dialog.dismiss()
                    setContentView(webView)
                    webView.loadUrl("file:///android_asset/business.html")
                    businessUnlocked = true
                    checkForBusinessUpdate()
                    webView.post { webView.scrollTo(0, 0) }
                } else {
                    input.text.clear()
                    Toast.makeText(this, "Onjuiste bedrijfscode.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    @Deprecated("Deprecated in Android SDK")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function(){const qr=document.getElementById('qrModal');if(qr&&qr.classList.contains('open')){closeQrModal();return 'closed'}const m=document.getElementById('orderModal');if(m&&m.classList.contains('open')){closeOrderModal();return 'closed'}if((typeof ordersScreenOpen!=='undefined'&&ordersScreenOpen)||(typeof historyScreenOpen!=='undefined'&&historyScreenOpen)){backToDashboard();return 'dashboard'}return 'none'})()"
        ) { result ->
            if (result != "\"closed\"" && result != "\"dashboard\"") {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }
}
