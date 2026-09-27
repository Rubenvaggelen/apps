package com.gmailorg.carradio

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object RemoteUsbMusicClient {
    private const val ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/music.php"
    @Volatile private var sessionToken = ""
    @Volatile private var sessionExpires = 0L

    class AuthRequired : Exception()

    data class RemoteFile(
        val deviceId: String,
        val stickId: String,
        val path: String,
        val name: String,
        val folder: String,
        val title: String,
        val artist: String,
        val album: String
    ) {
        val displayName: String
            get() = title.ifBlank { name }
    }

    data class RemoteStick(
        val deviceId: String,
        val deviceName: String,
        val stickId: String,
        val stickName: String,
        val files: List<RemoteFile>
    )

    fun hasToken(context: Context): Boolean =
        sessionToken.isNotBlank() && System.currentTimeMillis() < sessionExpires

    fun clearToken(context: Context) {
        sessionToken = ""
        sessionExpires = 0L
    }

    fun login(context: Context, pin: String): Boolean {
        val connection = open(ENDPOINT + "?action=login", "POST")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use {
            it.write(JSONObject().put("pin", pin).toString())
        }

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            return false
        }

        val json = JSONObject(readBody(connection))
        connection.disconnect()
        val token = json.optString("token").trim()
        if (token.isBlank()) return false

        val seconds = json.optLong("expires_in", 3600L)
        sessionToken = token
        sessionExpires =
            System.currentTimeMillis() + (seconds.coerceAtLeast(120L) - 60L) * 1000L
        return true
    }

    fun catalog(context: Context): List<RemoteStick> {
        val token = token(context)
        val connection = open(ENDPOINT + "?action=catalog", "GET")
        connection.setRequestProperty("Authorization", "Bearer " + token)

        val code = connection.responseCode
        if (code == 401 || code == 403) {
            connection.disconnect()
            clearToken(context)
            throw AuthRequired()
        }
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("Serverfout " + code)
        }

        val json = JSONObject(readBody(connection))
        connection.disconnect()
        val sticks = json.optJSONArray("sticks") ?: return emptyList()
        val result = mutableListOf<RemoteStick>()

        for (i in 0 until sticks.length()) {
            val item = sticks.optJSONObject(i) ?: continue
            val deviceId = item.optString("device_id").trim()
            val stickId = item.optString("stick_id").trim()
            if (deviceId.isBlank() || stickId.isBlank()) continue

            val deviceName = item.optString("device_name", deviceId).trim().ifBlank { deviceId }
            val stickName = item.optString("stick_name", "USB").trim().ifBlank { "USB" }
            val files = mutableListOf<RemoteFile>()
            val array = item.optJSONArray("files")

            if (array != null) {
                for (j in 0 until array.length()) {
                    val f = array.optJSONObject(j) ?: continue
                    val path = f.optString("path").trim()
                    if (path.isBlank() || !f.optBoolean("cached", false)) continue
                    files += RemoteFile(
                        deviceId = deviceId,
                        stickId = stickId,
                        path = path,
                        name = f.optString("name", path.substringAfterLast('/')).trim(),
                        folder = f.optString("folder").trim(),
                        title = f.optString("title").trim(),
                        artist = f.optString("artist").trim(),
                        album = f.optString("album").trim()
                    )
                }
            }

            if (files.isNotEmpty()) {
                result += RemoteStick(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    stickId = stickId,
                    stickName = stickName,
                    files = files
                )
            }
        }

        return result.sortedWith(
            compareBy<RemoteStick> { it.deviceName.lowercase() }
                .thenBy { it.stickName.lowercase() }
        )
    }

    fun streamUrl(context: Context, file: RemoteFile): String {
        val token = token(context)
        return ENDPOINT + "?action=stream" +
            "&token=" + enc(token) +
            "&device=" + enc(file.deviceId) +
            "&stick=" + enc(file.stickId) +
            "&path=" + enc(file.path)
    }

    private fun token(context: Context): String {
        if (!hasToken(context)) {
            clearToken(context)
            throw AuthRequired()
        }
        return sessionToken.ifBlank { throw AuthRequired() }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TheOneCar/USBMusic")
        }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return BufferedReader(stream.reader(StandardCharsets.UTF_8)).use { it.readText() }
    }
}
