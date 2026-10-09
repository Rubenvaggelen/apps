package com.gmailorg.hub

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Main licensing identity deliberately uses noBackupFilesDir.
 * App updates preserve this directory; uninstall/reinstall removes it.
 * Even if Android restores legacy device preferences, a reinstall gets a NEW install ID.
 */
object MainLicenseClient {
    private const val ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/licenses.php"
    private const val ID_FILE = "the-one-main-install-id"
    private const val TOKEN_FILE = "the-one-main-license-token"
    private const val SEEN_FILE = "the-one-main-license-verified-at"
    private const val OFFLINE_GRACE_MS = 24L * 60L * 60L * 1000L

    data class State(
        val enabled: Boolean,
        val allowed: Boolean,
        val mode: String,
        val pending: Boolean = false
    )

    fun installationId(context: Context): String {
        val file = File(context.noBackupFilesDir, ID_FILE)
        val stored = runCatching { file.readText().trim() }.getOrDefault("")
        if (runCatching { UUID.fromString(stored) }.isSuccess) return stored
        val generated = UUID.randomUUID().toString()
        file.writeText(generated)
        return generated
    }

    private fun readToken(context: Context): String =
        runCatching { File(context.noBackupFilesDir, TOKEN_FILE).readText().trim() }.getOrDefault("")

    private fun storeToken(context: Context, token: String) {
        require(Regex("^[a-fA-F0-9]{64}$").matches(token))
        File(context.noBackupFilesDir, TOKEN_FILE).writeText(token)
    }

    private fun setVerified(context: Context) {
        File(context.noBackupFilesDir, SEEN_FILE).writeText(System.currentTimeMillis().toString())
    }

    private fun cachedAllowed(context: Context): Boolean {
        val verified = runCatching {
            File(context.noBackupFilesDir, SEEN_FILE).readText().trim().toLong()
        }.getOrDefault(0L)
        return readToken(context).isNotBlank() &&
            verified > 0 && System.currentTimeMillis() - verified in 0..OFFLINE_GRACE_MS
    }

    private fun firstInstalledAt(context: Context): Long {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
    }

    private fun payload(context: Context) = JSONObject()
        .put("app", "main")
        .put("device_id", MainDeviceRegistry.deviceId(context))
        .put("installation_id", installationId(context))
        .put("first_installed_ms", firstInstalledAt(context))
        .put("person", MainDeviceRegistry.personName(context))

    private fun request(action: String, payload: JSONObject): JSONObject {
        val connection = (URL("$ENDPOINT?action=$action").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = false
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(response) }.getOrDefault(JSONObject())
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("error", "Licentieserver tijdelijk niet bereikbaar"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun status(context: Context): State {
        return try {
            val query = payload(context).put("token", readToken(context))
            val json = request("status", query)
            val enabled = json.optBoolean("enabled", false)
            val allowed = json.optBoolean("allowed", false)
            val mode = json.optString("mode", "")
            val credential = json.optJSONObject("credential")?.optString("token", "").orEmpty()
            if (credential.isNotBlank()) storeToken(context, credential)
            if (allowed && enabled && (readToken(context).isNotBlank())) setVerified(context)
            State(enabled, allowed, mode)
        } catch (_: Exception) {
            // Only an already locally activated install receives a short offline
            // grace period. Fresh installations cannot activate while offline.
            State(true, cachedAllowed(context), "server_offline")
        }
    }

    fun redeem(context: Context, code: String): State {
        val json = request("redeem", payload(context).put("code", code.trim().uppercase()))
        if (!json.optBoolean("allowed", false)) {
            return State(true, false, json.optString("error", "invalid_code"))
        }
        val token = json.optJSONObject("credential")?.optString("token", "").orEmpty()
        storeToken(context, token)
        setVerified(context)
        return State(true, true, "code")
    }

    fun askForAccess(context: Context): State {
        val json = request("request", payload(context))
        return State(true, false, "pending", pending = json.optString("status") == "pending")
    }
}
