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
data class MainPendingLicenseRequest(
    val id: String,
    val app: String,
    val person: String,
    val deviceId: String,
    val installationId: String,
    val createdAt: String
)

object MainLicenseClient {
    private const val ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/licenses.php"
    private const val ID_FILE = "the-one-main-install-id"
    private const val TOKEN_FILE = "the-one-main-license-token"
    private const val SEEN_FILE = "the-one-main-license-verified-at"
    private const val REQUEST_ID_FILE = "the-one-main-request-id"
    private const val REQUEST_SECRET_FILE = "the-one-main-request-secret"
    private const val OWNER_TOKEN_FILE = "the-one-main-owner-pairing-token"
    private const val OFFLINE_GRACE_MS = 24L * 60L * 60L * 1000L
    // All previously authorized Main installations predate the protected pilot.
    // Only installs first created on/after the pilot date enter pre-enforcement
    // owner approval. This prevents a fleet-wide prompt for legacy updates.
    // This is pilot UX gating, not a substitute for server enforcement.
    private const val PILOT_NEW_INSTALL_CUTOFF_MS = 1791590400000L // 2026-10-10 00:00 UTC

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

    /** Used by the Main registry heartbeat; never transmit the token in logs or URLs. */
    fun heartbeatCredential(context: Context): Pair<String, String>? {
        val token = readToken(context)
        if (!Regex("^[a-fA-F0-9]{64}$").matches(token)) return null
        return installationId(context) to token
    }

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
            // A pending owner approval remains on the server until approved.
            // The applicant's private request secret lets only that installation
            // claim the approved grant without copying a code between devices.
            claimApprovedRequest(context)

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

    @Synchronized
    fun askForAccess(context: Context): State {
        val json = request("request", payload(context))
        val requestId = json.optString("request_id", "")
        val secret = json.optString("request_secret", "")
        if (requestId.matches(Regex("^[a-f0-9]{24}$")) &&
            secret.matches(Regex("^[a-f0-9]{64}$"))) {
            File(context.noBackupFilesDir, REQUEST_ID_FILE).writeText(requestId)
            File(context.noBackupFilesDir, REQUEST_SECRET_FILE).writeText(secret)
        }
        return State(true, false, "pending", pending =
            json.optString("status") == "pending" || json.optString("status") == "approved")
    }

    private fun claimApprovedRequest(context: Context) {
        val id = runCatching { File(context.noBackupFilesDir, REQUEST_ID_FILE).readText().trim() }.getOrDefault("")
        val secret = runCatching { File(context.noBackupFilesDir, REQUEST_SECRET_FILE).readText().trim() }.getOrDefault("")
        if (!id.matches(Regex("^[a-f0-9]{24}$")) ||
            !secret.matches(Regex("^[a-f0-9]{64}$"))) return
        val result = request("claim", payload(context)
            .put("request_id", id).put("request_secret", secret))
        if (result.optBoolean("allowed", false)) {
            val token = result.optJSONObject("credential")?.optString("token", "").orEmpty()
            storeToken(context, token)
            setVerified(context)
            File(context.noBackupFilesDir, REQUEST_ID_FILE).delete()
            File(context.noBackupFilesDir, REQUEST_SECRET_FILE).delete()
        }
    }

    private fun ownerToken(context: Context): String =
        runCatching { File(context.noBackupFilesDir, OWNER_TOKEN_FILE).readText().trim() }.getOrDefault("")

    fun needsApprovalBeforeEnforcement(context: Context): Boolean {
        val installedAt = firstInstalledAt(context)
        if (installedAt <= 0L || installedAt < PILOT_NEW_INSTALL_CUTOFF_MS) return false
        // An owner paired to Dev Hub is already a distinct authenticated role.
        if (MainDeviceRegistry.isLocallyOwner(context) && ownerIsPaired(context)) return false
        return heartbeatCredential(context) == null
    }

    fun ownerIsPaired(context: Context): Boolean =
        ownerToken(context).matches(Regex("^[a-f0-9]{64}$"))

    /** Call only after a real cPanel-authenticated owner created a one-time code. */
    fun pairOwner(context: Context, pairingCode: String): Boolean {
        if (!MainDeviceRegistry.isLocallyOwner(context)) return false
        val json = request("owner_pair",
            payload(context).put("pairing_code", pairingCode.trim().uppercase()))
        if (!json.optBoolean("paired", false)) return false
        val token = json.optString("owner_token", "")
        if (!token.matches(Regex("^[a-f0-9]{64}$"))) return false
        File(context.noBackupFilesDir, OWNER_TOKEN_FILE).writeText(token)
        return true
    }

    fun pendingOwnerApprovals(context: Context): List<MainPendingLicenseRequest> {
        if (!MainDeviceRegistry.isLocallyOwner(context) || !ownerIsPaired(context)) {
            return emptyList()
        }
        val json = request("owner_pending", payload(context).put("owner_token", ownerToken(context)))
        val items = json.optJSONArray("requests") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                add(MainPendingLicenseRequest(
                    id = entry.optString("id", ""),
                    app = entry.optString("app", "main"),
                    person = entry.optString("person", ""),
                    deviceId = entry.optString("device", ""),
                    installationId = entry.optString("installation_id", ""),
                    createdAt = entry.optString("created_at", "")
                ))
            }
        }
    }

    /** Deletes one exact Main installation after server-side paired-owner verification. */
    fun removeOwnerDevice(context: Context, targetDeviceId: String, registeredAt: String) {
        require(MainDeviceRegistry.isLocallyOwner(context) && ownerIsPaired(context)) {
            "Beveiligde eigenaarkoppeling vereist"
        }
        require(targetDeviceId.isNotBlank() && registeredAt.isNotBlank()) {
            "Apparaat-ID of registratiedatum ontbreekt"
        }
        val payload = payload(context)
            .put("owner_token", ownerToken(context))
            .put("target_device_id", targetDeviceId)
            .put("expected_registered", registeredAt)
        val url = URL("https://rubenvanaggelen.com/the-one-remote-api/owner-remove-device.php")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 12000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = false
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(response) }.getOrDefault(JSONObject())
            if (status !in 200..299 || !result.optBoolean("ok", false)) {
                throw IllegalStateException(result.optString("message", "Verwijderen mislukt ($status)"))
            }
            require(result.optString("removed_device_id") == targetDeviceId) {
                "Server heeft verwijderen niet bevestigd"
            }
        } finally {
            connection.disconnect()
        }
    }

    fun approveOwnerRequest(context: Context, requestId: String): Boolean {
        if (!MainDeviceRegistry.isLocallyOwner(context) || !ownerIsPaired(context)) return false
        val json = request("owner_approve", payload(context)
            .put("owner_token", ownerToken(context))
            .put("request_id", requestId))
        return json.optBoolean("approved", false)
    }
}
