package com.gmailorg.hub

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checkt bij het opstarten of er een nieuwere versie van de app beschikbaar
 * is (via GitHub Releases, die de build-workflow automatisch aanmaakt), en
 * toont zo ja een dialoogvenster met een link om de nieuwe APK te downloaden.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL = "https://api.github.com/repos/Rubenvaggelen/apps/releases/latest"

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
                val releaseUrl = json.optString("html_url", "")

                if (latestVersionCode > BuildConfig.VERSION_CODE && releaseUrl.isNotBlank()) {
                    val context2 = context.applicationContext
                    (context as? android.app.Activity)?.runOnUiThread {
                        showUpdateDialog(context, releaseUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update-check mislukt (geen probleem, gewoon overslaan)", e)
            }
        }.start()
    }

    private fun showUpdateDialog(context: Context, releaseUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Nieuwe versie beschikbaar")
            .setMessage("Er is een nieuwere versie van The One. Wil je die nu downloaden?")
            .setPositiveButton("Downloaden") { _, _ ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
