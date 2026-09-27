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
        val album: String,
        val cached: Boolean
    ) {
        val displayName: String
            get() = title.ifBlank { name }
    }

    data class RemoteStick(
        val deviceId: String,
        val deviceName: String,
        val stickId: String,
        val stickName: String,
        val files: List<RemoteFile>,
        val totalFiles: Int
    )

    data class FavoriteItem(
        val id: String,
        val kind: String,
        val title: String,
        val sourceLabel: String,
        val deviceId: String,
        val stickId: String,
        val path: String,
        val url: String
    )

    fun hasToken(context: Context): Boolean =
        sessionToken.isNotBlank() && System.currentTimeMillis() < sessionExpires

    fun clearToken(context: Context) {
        sessionToken = ""
        sessionExpires = 0L
    }

    fun loginForBrowsing(context: Context): Boolean {
        val connection = open(ENDPOINT + "?action=browse-login", "POST")
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
        val connection = open(ENDPOINT + "?action=catalog&include_inactive=1", "GET")
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
            val totalFiles = array?.length() ?: 0

            if (array != null) {
                for (j in 0 until array.length()) {
                    val f = array.optJSONObject(j) ?: continue
                    val path = f.optString("path").trim()
                    if (path.isBlank()) continue
                    val cached = f.optBoolean("cached", false)
                    val name = f.optString("name", path.substringAfterLast('/')).trim()
                    if (isMacMetadataFile(path, name)) continue
                    files += RemoteFile(
                        deviceId = deviceId,
                        stickId = stickId,
                        path = path,
                        name = name,
                        folder = f.optString("folder").trim(),
                        title = f.optString("title").trim(),
                        artist = f.optString("artist").trim(),
                        album = f.optString("album").trim(),
                        cached = cached
                    )
                }
            }

            if (totalFiles > 0) {
                result += RemoteStick(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    stickId = stickId,
                    stickName = stickName,
                    files = files,
                    totalFiles = totalFiles
                )
            }
        }

        return result.sortedWith(
            compareBy<RemoteStick> { it.deviceName.lowercase() }
                .thenBy { it.stickName.lowercase() }
        )
    }

    private fun isMacMetadataFile(path: String, name: String): Boolean {
        val cleanPath = path.replace('\\', '/')
        val segments = cleanPath.split('/').filter { it.isNotBlank() }
        val fileName = name.ifBlank { segments.lastOrNull().orEmpty() }

        if (fileName.startsWith("._")) return true
        if (fileName.equals(".DS_Store", ignoreCase = true)) return true

        return segments.any { segment ->
            segment.equals("__MACOSX", ignoreCase = true) ||
                segment.equals(".Spotlight-V100", ignoreCase = true) ||
                segment.equals(".Trashes", ignoreCase = true) ||
                segment.equals(".fseventsd", ignoreCase = true)
        }
    }

    fun favorites(context: Context): List<FavoriteItem> {
        val token = token(context)
        val connection = open(ENDPOINT + "?action=favorites-list", "GET")
        connection.setRequestProperty("Authorization", "Bearer " + token)
        val code = connection.responseCode
        if (code == 401 || code == 403) {
            connection.disconnect()
            clearToken(context)
            throw AuthRequired()
        }
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("Favorieten konden niet worden geladen")
        }
        val json = JSONObject(readBody(connection))
        connection.disconnect()
        val array = json.optJSONArray("items") ?: return emptyList()
        val result = mutableListOf<FavoriteItem>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            result += FavoriteItem(
                id = o.optString("id").trim(),
                kind = o.optString("kind").trim(),
                title = o.optString("title").trim(),
                sourceLabel = o.optString("source_label").trim(),
                deviceId = o.optString("device_id").trim(),
                stickId = o.optString("stick_id").trim(),
                path = o.optString("path").trim(),
                url = o.optString("url").trim()
            )
        }
        return result
    }

    fun setMixFavorite(
        context: Context,
        title: String,
        url: String,
        favorite: Boolean
    ): Boolean = setFavorite(
        context,
        JSONObject()
            .put("kind", "mix")
            .put("title", title)
            .put("source_label", "The One Mixes")
            .put("url", url)
            .put("favorite", favorite)
    )

    fun setUsbFavorite(
        context: Context,
        stick: RemoteStick,
        file: RemoteFile,
        favorite: Boolean
    ): Boolean = setFavorite(
        context,
        JSONObject()
            .put("kind", "usb")
            .put("title", file.displayName)
            .put("source_label", "Shared Media • " + stick.deviceName + " • " + stick.stickName)
            .put("device_id", file.deviceId)
            .put("stick_id", file.stickId)
            .put("path", file.path)
            .put("favorite", favorite)
    )

    fun setFavoriteItem(
        context: Context,
        item: FavoriteItem,
        favorite: Boolean
    ): Boolean {
        val body = JSONObject()
            .put("kind", item.kind)
            .put("title", item.title)
            .put("source_label", item.sourceLabel)
            .put("favorite", favorite)
        if (item.kind.equals("usb", ignoreCase = true)) {
            body.put("device_id", item.deviceId)
                .put("stick_id", item.stickId)
                .put("path", item.path)
        } else {
            body.put("url", item.url)
        }
        return setFavorite(context, body)
    }

    private fun setFavorite(context: Context, body: JSONObject): Boolean {
        val token = token(context)
        body.put("request_device_id", CarFamilyAccess.deviceId(context))
        val connection = open(ENDPOINT + "?action=favorites-set", "POST")
        connection.setRequestProperty("Authorization", "Bearer " + token)
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use {
            it.write(body.toString())
        }
        val code = connection.responseCode
        if (code == 401 || code == 403) {
            connection.disconnect()
            clearToken(context)
            throw AuthRequired()
        }
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("Favoriet opslaan mislukt")
        }
        val json = JSONObject(readBody(connection))
        connection.disconnect()
        return json.optBoolean("favorite", false)
    }

    fun streamUrl(context: Context, file: RemoteFile): String {
        val token = token(context)
        return ENDPOINT + "?action=stream" +
            "&token=" + enc(token) +
            "&device=" + enc(file.deviceId) +
            "&stick=" + enc(file.stickId) +
            "&path=" + enc(file.path)
    }

    fun streamUrl(context: Context, favorite: FavoriteItem): String {
        if (!favorite.kind.equals("usb", ignoreCase = true)) {
            return favorite.url
        }
        val token = token(context)
        return ENDPOINT + "?action=stream" +
            "&token=" + enc(token) +
            "&device=" + enc(favorite.deviceId) +
            "&stick=" + enc(favorite.stickId) +
            "&path=" + enc(favorite.path)
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
