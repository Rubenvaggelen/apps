package com.gmailorg.hub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class RadioActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radio)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        findViewById<View>(R.id.radio538Button).setOnClickListener {
            openUrl("https://www.538.nl/radio/luisteren")
        }
        findViewById<View>(R.id.radioFunxButton).setOnClickListener {
            openUrl("https://www.funx.nl/")
        }
        findViewById<View>(R.id.radioSkyButton).setOnClickListener {
            openUrl("https://www.skyradio.nl/")
        }
        findViewById<View>(R.id.radioLimFmButton).setOnClickListener {
            openUrl("https://www.limfmsu.com/")
        }
        findViewById<View>(R.id.radioBoskopuButton).setOnClickListener {
            openUrl("https://radioboskopu.sr/")
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
