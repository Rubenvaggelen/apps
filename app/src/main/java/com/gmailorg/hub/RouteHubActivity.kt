package com.gmailorg.hub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class RouteHubActivity : AppCompatActivity() {
    private val mailUrl = BuildConfig.APP_BASE_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_hub)
        MenuButtonHelper.attach(this)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.routePlannerButton).setOnClickListener { openRoutePlanner() }
        findViewById<Button>(R.id.fuelPricesButton).setOnClickListener {
            startActivity(Intent(this, FuelPricesActivity::class.java))
        }
    }

    private fun openRoutePlanner() {
        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(this, Uri.parse("$mailUrl#route-standalone"))
    }
}
