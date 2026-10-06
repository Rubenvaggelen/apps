package com.gmailorg.runcoach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Run-only releases; installation always uses Android's package installer. */
class RunUpdater(private val activity: Activity) {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var closed = false
    private var lastCheck = 0L
    private var offeredVersion = 0
    private var awaitingPermission = false
    private var resumed = false
    private val apk get() = File(activity.filesDir, "updates/run-update.apk")
    private val tick = object : Runnable {
        override fun run() {
            check(false)
            handler.postDelayed(this, 15 * 60 * 1000L)
        }
    }

    fun onResume() {
        resumed = true
        if (awaitingPermission) {
            awaitingPermission = false
            if (activity.packageManager.canRequestPackageInstalls()) install()
            else toast("Installeren niet toegestaan. Gebruik Updates controleren om opnieuw te proberen.")
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }

    private fun idle() = resumed && RunRepository.snapshot.status in listOf(RunStatus.IDLE, RunStatus.FINISHED)
    private fun ui(action: () -> Unit) {
        handler.post { if (!closed && !activity.isFinishing && !activity.isDestroyed) action() }
    }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    fun check(manual: Boolean) {
        if (closed || busy) return
        if (!idle()) {
            if (manual) toast("Updates worden aangeboden na je training.")
            return
        }
        if (!manual && System.currentTimeMillis() - lastCheck < 15 * 60 * 1000L) return
        lastCheck = System.currentTimeMillis()
        busy = true
        executor.execute {
            val result = runCatching {
                val conn = URL("https://api.github.com/repos/Rubenvaggelen/apps/releases?per_page=100").openConnection() as HttpURLConnection
                val releases = try {
                    conn.connectTimeout = 15000
                    conn.readTimeout = 20000
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.setRequestProperty("User-Agent", "The-One-Run")
                    require(conn.responseCode == 200) { "Updatecontrole mislukt (HTTP ${conn.responseCode})" }
                    JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                } finally { conn.disconnect() }
                var best: Pair<Int, String>? = null
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                    val version = RunUpdatePolicy.version(release.optString("tag_name")) ?: continue
                    if (version <= BuildConfig.VERSION_CODE || version <= (best?.first ?: 0)) continue
                    val assets = release.optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        if (asset.optString("name") != "runcoach-debug.apk") continue
                        val url = asset.optString("browser_download_url")
                        if (RunUpdatePolicy.validUrl(version, url)) best = version to url
                    }
                }
                best
            }
            ui {
                busy = false
                result.fold(onSuccess = { update ->
                    if (update == null) {
                        if (manual) toast("The One Run is bijgewerkt (build ${BuildConfig.VERSION_CODE}).")
                    } else if (idle() && (manual || offeredVersion != update.first)) {
                        offeredVersion = update.first
                        AlertDialog.Builder(activity)
                            .setTitle("The One Run-update")
                            .setMessage("Build ${update.first} is beschikbaar. Download en installeer vanuit Run; je instellingen en trainingen blijven behouden.")
                            .setPositiveButton("Bijwerken") { _, _ -> download(update.second) }
                            .setNegativeButton("Later", null).show()
                    }
                }, onFailure = {
                    if (manual) toast(it.message ?: "Updatecontrole mislukt. Probeer opnieuw.")
                })
            }
        }
    }

    private fun download(url: String) {
        if (busy || closed || !idle()) return
        busy = true
        toast("Run-update wordt op de achtergrond gedownload.")
        executor.execute {
            val result = runCatching {
                apk.parentFile!!.mkdirs()
                val temp = File(apk.parentFile, "run-update.tmp")
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 20000
                        conn.readTimeout = 30000
                        conn.setRequestProperty("User-Agent", "The-One-Run")
                        require(conn.responseCode == 200) { "Download mislukt (HTTP ${conn.responseCode})" }
                        conn.inputStream.use { input ->
                            temp.outputStream().use { output ->
                                val buffer = ByteArray(32768)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    require(total <= 100 * 1024 * 1024) { "Updatebestand te groot" }
                                    output.write(buffer, 0, n)
                                }
                                require(total > 0) { "Lege download" }
                            }
                        }
                    } finally { conn.disconnect() }
                    verify(temp)
                    require(temp.renameTo(apk)) { "Update kon niet worden opgeslagen" }
                } finally { temp.delete() }
            }
            ui {
                busy = false
                result.fold(onSuccess = {
                    if (idle()) install()
                    else toast("Update klaar. Installeer na je training via Updates controleren.")
                }, onFailure = { toast(it.message ?: "Download mislukt. Probeer opnieuw.") })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(file: File) {
        val pm = activity.packageManager
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Ongeldig of onvolledig APK-bestand")
        val current = pm.getPackageInfo(activity.packageName, flags)
        val newVersion = if (android.os.Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val oldVersion = if (android.os.Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        require(candidate.packageName == activity.packageName && newVersion > oldVersion) {
            "Dit is geen nieuwere The One Run-update"
        }
        val downloaded = (if (android.os.Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.apkContentsSigners else candidate.signatures)?.map { it.toCharsString() }?.toSet()
        val installed = (if (android.os.Build.VERSION.SDK_INT >= 28) current.signingInfo?.apkContentsSigners else current.signatures)?.map { it.toCharsString() }?.toSet()
        require(!downloaded.isNullOrEmpty() && downloaded == installed) { "Ondertekening van de update komt niet overeen" }
    }

    private fun install() {
        if (!idle()) return
        runCatching {
            verify(apk)
            if (!activity.packageManager.canRequestPackageInstalls()) {
                awaitingPermission = true
                AlertDialog.Builder(activity)
                    .setTitle("Run mag updates installeren")
                    .setMessage("Sta installatie vanuit The One Run eenmalig toe. Daarna opent de installatie van deze update.")
                    .setPositiveButton("Instellen") { _, _ ->
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }
                    .setNegativeButton("Later") { _, _ -> awaitingPermission = false }.show()
            } else {
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }.onFailure { toast(it.message ?: "Installatie kon niet worden geopend") }
    }
}
