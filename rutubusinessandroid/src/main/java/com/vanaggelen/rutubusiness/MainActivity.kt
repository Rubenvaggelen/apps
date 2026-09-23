package com.vanaggelen.rutubusiness

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
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
            webViewClient = object : WebViewClient() {
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
