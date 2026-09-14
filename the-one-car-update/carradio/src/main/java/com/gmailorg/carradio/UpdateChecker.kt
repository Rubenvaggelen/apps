package com.gmailorg.carradio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checkt bij het opstarten of er een nieuwere versie van de autoradio-app
 * beschikbaar is (via GitHub Releases, die de build-workflow automatisch
 * aanmaakt), en toont zo ja een dialoogvenster met een directe downloadlink
 * naar de carradio-APK (niet naar de releasepagina, die ook de telefoon-APK
 * bevat).
 */
object UpdateChecker {

    private const val TAG = "CarRadioUpdateChecker"
    private const val RELEASES_URL = "https://api.github.com/repos/Rubenvaggelen/apps/releases/latest"
    private const val ASSET_NAME = "carradio-debug.apk"

    fun checkForUpdate(context: Context) {
        Thread {
            try {
                val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "") // bijv. "v42"
                val latestVersionCode = tagName.removePrefix("v").toIntOrNull() ?: return@Thread

                var downloadUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name") == ASSET_NAME) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                // Val terug op de releasepagina als het specifieke bestand niet gevonden is.
                if (downloadUrl.isBlank()) downloadUrl = json.optString("html_url", "")

                if (latestVersionCode > BuildConfig.VERSION_CODE && downloadUrl.isNotBlank()) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        showUpdateDialog(context, downloadUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update-check mislukt (geen probleem, gewoon overslaan)", e)
            }
        }.start()
    }

    private fun showUpdateDialog(context: Context, downloadUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Nieuwe versie beschikbaar")
            .setMessage("Er is een nieuwere versie van de autoradio-app. Wil je die nu downloaden?")
            .setPositiveButton("Downloaden") { _, _ ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
