package com.vanaggelen.rutubusiness

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object RutuBusinessUpdateChecker {
    private const val UPDATE_URL = "https://rubenvanaggelen.com/rutu-updates/business-android.json"
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun checkForUpdate(activity: Activity, manual: Boolean = false) {
        if (activity.isFinishing) return
        Thread {
            try {
                val connection = URL(UPDATE_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val code = connection.responseCode
                if (code !in 200..299) {
                    connection.disconnect()
                    return@Thread
                }
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                connection.disconnect()

                val latest = json.optInt("versionCode", 0)
                val apkUrl = json.optString("url", "")
                val sha256 = json.optString("sha256", "").lowercase()
                if (latest <= currentVersionCode(activity)) {
                    if (manual) activity.runOnUiThread { Toast.makeText(activity, "De bedrijfsapp is bijgewerkt. / The business app is up to date.", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val trusted = try {
                    val u = Uri.parse(apkUrl)
                    u.scheme == "https" && u.host == "rubenvanaggelen.com" &&
                        u.path == "/rutu-updates/Rutu-BBQ-Bedrijf-Android.apk"
                } catch (_: Exception) { false }
                if (!trusted || !Regex("^[a-f0-9]{64}$").matches(sha256)) {
                    if (manual) activity.runOnUiThread { Toast.makeText(activity, "Updategegevens zijn ongeldig. / Update information is invalid.", Toast.LENGTH_LONG).show() }
                    return@Thread
                }

                activity.runOnUiThread {
                    if (!activity.isFinishing) showDialog(activity, latest, apkUrl, sha256)
                }
            } catch (_: Exception) {
                if (manual) activity.runOnUiThread { Toast.makeText(activity, "Updatecontrole mislukt. Probeer het later opnieuw. / Update check failed. Please try again later.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun currentVersionCode(context: Context): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: Exception) {
            0L
        }
    }

    private fun showDialog(activity: Activity, versionCode: Int, apkUrl: String, sha256: String) {
        AlertDialog.Builder(activity)
            .setTitle("Rutu BBQ Bedrijf update / Rutu BBQ Business update")
            .setMessage("Er staat een nieuwe versie van de bedrijfsapp klaar. Wil je nu bijwerken?\nA new version of the business app is available. Update now?")
            .setPositiveButton("Bijwerken") { _, _ -> startUpdate(activity, versionCode, apkUrl, sha256) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(activity: Activity, versionCode: Int, apkUrl: String, sha256: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
                Toast.makeText(
                    activity,
                    "Geef de bedrijfsapp installatierechten en kies daarna opnieuw Controleer op updates.\nAllow the business app to install updates, then choose Check for updates again.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {}
            return
        }

        try {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return
            val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val fileName = "Rutu-BBQ-Bedrijf-update-$versionCode.apk"
            if (dir != null) File(dir, fileName).delete()

            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Rutu BBQ Bedrijf update / Rutu BBQ Business update")
                .setDescription("Nieuwe versie wordt gedownload / New version is downloading")
                .setMimeType(APK_MIME)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)

            val id = manager.enqueue(request)
            Toast.makeText(activity, "Update wordt gedownload… / Update is downloading…", Toast.LENGTH_SHORT).show()
            Thread { waitForDownload(activity, manager, id, sha256) }.start()
        } catch (_: Exception) {
            Toast.makeText(activity, "Update kon niet worden gestart. / Update could not be started.", Toast.LENGTH_LONG).show()
        }
    }

    private fun waitForDownload(activity: Activity, manager: DownloadManager, id: Long, expectedSha: String) {
        val until = System.currentTimeMillis() + 10 * 60 * 1000L
        while (System.currentTimeMillis() < until) {
            var cursor: Cursor? = null
            try {
                cursor = manager.query(DownloadManager.Query().setFilterById(id))
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (index >= 0) cursor.getInt(index) else DownloadManager.STATUS_FAILED
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val uri = manager.getUriForDownloadedFile(id)
                        if (uri == null || (expectedSha.isNotBlank() && sha256(activity, uri) != expectedSha)) {
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Updatecontrole mislukt. Update niet geïnstalleerd. / Update verification failed. Update was not installed.", Toast.LENGTH_LONG).show()
                            }
                            return
                        }
                        activity.runOnUiThread { openInstaller(activity, uri) }
                        return
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Download van de update is mislukt. / Update download failed.", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                }
            } catch (_: Exception) {
            } finally {
                cursor?.close()
            }
            try { Thread.sleep(700) } catch (_: InterruptedException) { return }
        }
    }

    private fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return ""
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openInstaller(activity: Activity, uri: Uri) {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (_: Exception) {
            Toast.makeText(activity, "Android kon de update niet openen. / Android could not open the update.", Toast.LENGTH_LONG).show()
        }
    }
}
