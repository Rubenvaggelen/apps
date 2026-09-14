package com.gmailorg.hub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class NewsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        findViewById<View>(R.id.newsStarnieuwsButton).setOnClickListener {
            openUrl("https://www.starnieuws.com/")
        }
        findViewById<View>(R.id.newsWaterkantButton).setOnClickListener {
            openUrl("https://www.waterkant.net/")
        }
        findViewById<View>(R.id.newsNuButton).setOnClickListener {
            openUrl("https://www.nu.nl/")
        }
        findViewById<View>(R.id.newsNosButton).setOnClickListener {
            openUrl("https://nos.nl/")
        }
        findViewById<View>(R.id.newsDwtButton).setOnClickListener {
            openUrl("https://dwtonline.com/")
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
