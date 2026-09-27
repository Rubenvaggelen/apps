package com.gmailorg.hub

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zoekt specifiek naar Android-releases met tags v###.
 * Andere The One Family releases (zoals windows-v###) worden genegeerd.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_URL =
        "https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=40"
    private const val ASSET_NAME = "app-debug.apk"

    fun checkForUpdate(context: Context) {
        Thread {
            try {
                val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "TheOneFamily-Android-Updater")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val releases = JSONArray(body)
                var bestVersion = 0
                var bestDownloadUrl = ""

                for (i in 0 until releases.length()) {
                    val release = releases.optJSONObject(i) ?: continue
                    if (release.optBoolean("draft", false)) continue

                    val tag = release.optString("tag_name", "").trim()
                    if (!tag.matches(Regex("^v\\d+$"))) continue

                    val version = tag.substring(1).toIntOrNull() ?: continue
                    if (version <= bestVersion) continue

                    var assetUrl = ""
                    val assets = release.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val asset = assets.optJSONObject(j) ?: continue
                            if (asset.optString("name") == ASSET_NAME) {
                                assetUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    if (assetUrl.isBlank()) continue

                    bestVersion = version
                    bestDownloadUrl = assetUrl
                }

                if (bestVersion > BuildConfig.VERSION_CODE && bestDownloadUrl.isNotBlank()) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        showUpdateDialog(context, bestDownloadUrl, bestVersion)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update-check mislukt; app blijft normaal bruikbaar", e)
            }
        }.start()
    }

    private fun showUpdateDialog(
        context: Context,
        downloadUrl: String,
        versionCode: Int
    ) {
        AlertDialog.Builder(context)
            .setTitle("Nieuwe versie beschikbaar")
            .setMessage("Er is een nieuwere versie van The One beschikbaar. (build " + versionCode + ")")
            .setPositiveButton("Downloaden") { _, _ ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
