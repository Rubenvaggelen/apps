package com.gmailorg.hub

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Restores the previously paired Main screen-control identity.
 * This does not create a new license, change the Family device registry,
 * or read/write passwords. The pairing token is stored in noBackupFilesDir
 * and is sent only by HTTPS to the existing owner-only screen relay.
 */
internal object ScreenOwnerClient {
    private const val INSTALL_ID_FILE = "the-one-main-install-id"
    private const val OWNER_TOKEN_FILE = "the-one-main-owner-pairing-token"
    private const val SCREEN_API = "https://rubenvanaggelen.com/the-one-remote-api/screen-control.php"
    private const val LICENSE_API = "https://rubenvanaggelen.com/the-one-remote-api/licenses.php"
    private val tokenFormat = Regex("^[a-f0-9]{64}$")

    private fun installId(context: Context): String {
        val file = File(context.noBackupFilesDir, INSTALL_ID_FILE)
        val existing = runCatching { file.readText().trim() }.getOrDefault("")
        if (runCatching { UUID.fromString(existing) }.isSuccess) return existing
        val value = UUID.randomUUID().toString()
        file.writeText(value)
        return value
    }

    private fun ownerToken(context: Context): String =
        runCatching { File(context.noBackupFilesDir, OWNER_TOKEN_FILE).readText().trim() }
            .getOrDefault("")

    fun isPaired(context: Context): Boolean =
        !BuildConfig.IS_USER_APP &&
            ownerToken(context).matches(tokenFormat) &&
            runCatching {
                UUID.fromString(File(context.noBackupFilesDir, INSTALL_ID_FILE).readText().trim())
            }.isSuccess

    private fun ownerRequired(context: Context) {
        check(!BuildConfig.IS_USER_APP && MainDeviceRegistry.isLocallyOwner(context) &&
            !MainDeviceRegistry.isLocallyBlocked(context)) {
            "Alleen de geregistreerde eigenaar mag schermen bedienen."
        }
    }

    private fun body(context: Context): JSONObject {
        val installTime = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        } catch (_: PackageManager.NameNotFoundException) { 0L }
        return JSONObject()
            .put("app", "main")
            .put("device_id", MainDeviceRegistry.deviceId(context))
            .put("installation_id", installId(context))
            .put("first_installed_ms", installTime)
            .put("person", MainDeviceRegistry.personName(context))
    }

    private fun post(url: String, payload: JSONObject): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
        }
        try {
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(response) }.getOrDefault(JSONObject())
            if (status !in 200..299 || !result.optBoolean("ok", false)) {
                throw IllegalStateException(
                    if (status == 403) "Eigenaarskoppeling niet geldig. Koppel schermbediening opnieuw in Dev Hub."
                    else "Schermserver niet bereikbaar (HTTP $status)"
                )
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    fun pairOwner(context: Context, code: String): Boolean {
        ownerRequired(context)
        val trimmed = code.trim().uppercase()
        require(trimmed.length in 6..64 && trimmed.all { it.isLetterOrDigit() || it == '-' }) {
            "Ongeldige koppelcode"
        }
        val response = post("$LICENSE_API?action=owner_pair",
            body(context).put("pairing_code", trimmed))
        if (!response.optBoolean("paired", false)) return false
        val token = response.optString("owner_token", "")
        check(token.matches(tokenFormat)) { "Ongeldig antwoord van koppelserver" }
        File(context.noBackupFilesDir, OWNER_TOKEN_FILE).writeText(token)
        return true
    }

    fun screenTicket(context: Context, target: String, command: String): String {
        ownerRequired(context)
        check(isPaired(context)) { "Schermbediening nog niet aan je Main gekoppeld." }
        require(target in setOf("ruben", "surface", "hub"))
        require(command in setOf("ON", "OFF"))
        val result = post("$SCREEN_API?action=issue", body(context)
            .put("owner_token", ownerToken(context))
            .put("target", target)
            .put("command", command))
        val ticket = result.optString("ticket", "")
        check(ticket.matches(tokenFormat)) { "Onjuist ticket van schermserver" }
        return ticket
    }

    fun screenCommandStatus(context: Context, target: String, ticket: String): String {
        ownerRequired(context)
        check(isPaired(context)) { "Schermbediening nog niet gekoppeld" }
        require(target in setOf("ruben", "surface", "hub"))
        require(ticket.matches(tokenFormat))
        val result = post("$SCREEN_API?action=status", body(context)
            .put("owner_token", ownerToken(context))
            .put("target", target)
            .put("ticket", ticket))
        val value = result.optString("status", "unknown")
        require(value in setOf("pending", "executing", "done", "failed")) {
            "Onbekende schermstatus"
        }
        return value
    }
}
