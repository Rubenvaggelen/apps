package com.gmailorg.carradio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zoekt specifiek naar Android-releases met tags v###.
 * Andere The One Family releases (zoals windows-v###) worden genegeerd.
 */
object UpdateChecker {

    private const val TAG = "CarRadioUpdateChecker"
    private const val RELEASES_URL =
        "https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=40"
    private const val ASSET_NAME = "carradio-debug.apk"

    // Prefer the centrally hosted, read-only release feed. A failed response keeps GitHub fallback.
    private fun centralRelease(): Pair<Int, String>? {
        val conn = URL("https://theoneforlive.com/updates/feed.php?app=car").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 7000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "TheOneFamily-Android-Updater")
            if (conn.responseCode != 200) return null
            val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (data.optString("app") != "car" || data.optString("channel") != "stable") return null
            val version = data.optInt("versionCode", 0)
            val tag = "car-v" + version
            val expected = "https://github.com/Rubenvaggelen/apps/releases/download/" + tag + "/carradio-debug.apk"
            if (version <= 0 || data.optString("tag") != tag || data.optString("downloadUrl") != expected) return null
            return version to expected
        } finally {
            conn.disconnect()
        }
    }

    fun checkForUpdate(context: Context, manual: Boolean = false) {
        Thread {
            try {
                val central = try { centralRelease() } catch (e: Exception) {
                    Log.w(TAG, "Central feed unavailable, using GitHub fallback", e)
                    null
                }
                if (central != null) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        if (central.first > BuildConfig.VERSION_CODE) {
                            showUpdateDialog(context, central.second, central.first)
                        } else if (manual) {
                            AlertDialog.Builder(context)
                                .setTitle("Geen nieuwe update")
                                .setMessage("The One Car is al bijgewerkt. Je gebruikt build " + BuildConfig.VERSION_CODE + ".")
                                .setPositiveButton("OK", null).show()
                        }
                    }
                    return@Thread
                }
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
                    if (!tag.matches(Regex("^car-v\\d+$"))) continue

                    val version = tag.removePrefix("car-v").toIntOrNull() ?: continue
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

                (context as? android.app.Activity)?.runOnUiThread {
                    when {
                        bestVersion > BuildConfig.VERSION_CODE &&
                            bestDownloadUrl.isNotBlank() -> {
                            showUpdateDialog(context, bestDownloadUrl, bestVersion)
                        }

                        manual -> {
                            AlertDialog.Builder(context)
                                .setTitle("Geen nieuwe update")
                                .setMessage(
                                    "The One Car is al bijgewerkt. Je gebruikt build " +
                                        BuildConfig.VERSION_CODE + "."
                                )
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update-check mislukt; app blijft normaal bruikbaar", e)
                if (manual) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        AlertDialog.Builder(context)
                            .setTitle("Update zoeken mislukt")
                            .setMessage(
                                "The One Car kon nu niet controleren op updates. " +
                                    "Controleer de internetverbinding en probeer opnieuw."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
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
            .setMessage("Er is een nieuwere versie van The One Car beschikbaar. (build " + versionCode + ")")
            .setPositiveButton("Downloaden") { _, _ ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
