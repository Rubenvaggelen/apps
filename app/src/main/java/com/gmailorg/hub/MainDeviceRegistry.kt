package com.gmailorg.hub

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class MainRegisteredDevice(
    val id: String,
    val name: String,
    val platform: String,
    val version: String,
    val blocked: Boolean,
    val online: Boolean,
    val lastSeen: Long
)

object MainDeviceRegistry {
    private const val ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/devices.php"
    private const val PREFS = "main_device_registry"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_BLOCKED = "blocked"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun isLocallyBlocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLOCKED, false)

    fun heartbeat(context: Context): Boolean {
        val payload = JSONObject()
            .put("device_id", deviceId(context))
            .put("name", deviceName())
            .put("platform", "Android ${Build.VERSION.RELEASE}")
            .put("version", BuildConfig.VERSION_CODE.toString())

        val json = request("heartbeat", payload)
        val blocked = json.optBoolean("blocked", false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BLOCKED, blocked)
            .apply()
        return blocked
    }

    fun listDevices(pin: String): List<MainRegisteredDevice> {
        val json = request("list", JSONObject().put("pin", pin))
        val array = json.optJSONArray("devices") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    MainRegisteredDevice(
                        id = item.optString("device_id"),
                        name = item.optString("name", "Apparaat"),
                        platform = item.optString("platform", ""),
                        version = item.optString("version", ""),
                        blocked = item.optBoolean("blocked", false),
                        online = item.optBoolean("online", false),
                        lastSeen = item.optLong("last_seen", 0L)
                    )
                )
            }
        }
    }

    fun setBlocked(pin: String, deviceId: String, blocked: Boolean) {
        request(
            "set_blocked",
            JSONObject()
                .put("pin", pin)
                .put("device_id", deviceId)
                .put("blocked", blocked)
        )
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android apparaat" }
    }

    private fun request(action: String, body: JSONObject): JSONObject {
        val connection = (URL("$ENDPOINT?action=$action").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
        }

        connection.outputStream.use {
            it.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()

        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (code !in 200..299 || !json.optBoolean("ok", false)) {
            throw IllegalStateException(json.optString("error", "Serverfout $code"))
        }
        return json
    }
}
