package com.gmailorg.carradio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class NewsCarActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_car)
        val sites = listOf(
            R.id.newsStar to "https://www.starnieuws.com/",
            R.id.newsWaterkant to "https://www.waterkant.net/",
            R.id.newsNu to "https://www.nu.nl/",
            R.id.newsNos to "https://nos.nl/",
            R.id.newsDwt to "https://dwtonline.com/"
        )
        sites.forEach { (id, url) -> findViewById<Button>(id).setOnClickListener { open(url) } }
    }
    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "Geen browser gevonden", Toast.LENGTH_SHORT).show() }
    }
}
