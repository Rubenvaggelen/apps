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
    val personName: String,
    val platform: String,
    val version: String,
    val blocked: Boolean,
    val owner: Boolean,
    val musicRights: Boolean,
    val online: Boolean,
    val lastSeen: Long
)

data class WindowsConnectionDevice(
    val id: String,
    val name: String,
    val online: Boolean,
    val lastSeen: Long
)

object MainDeviceRegistry {
    private const val ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/devices.php"
    private const val PREFS = "main_device_registry"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_OWNER = "owner"
    private const val KEY_MUSIC_RIGHTS = "music_rights"
    private const val KEY_PERSON_NAME = "person_name"
    private const val KEY_THE_ONE = "the_one_profile"

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

    fun isLocallyOwner(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OWNER, false)

    fun hasMusicRights(context: Context): Boolean =
        isLocallyOwner(context) ||
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MUSIC_RIGHTS, false)

    fun personName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PERSON_NAME, "")
            .orEmpty()
            .trim()

    fun hasPersonName(context: Context): Boolean =
        personName(context).isNotBlank()

    fun isTheOneProfile(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_THE_ONE, false)

    fun savePersonRegistration(
        context: Context,
        personName: String,
        isTheOne: Boolean
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERSON_NAME, personName.trim())
            .putBoolean(KEY_THE_ONE, isTheOne)
            .apply()
    }

    fun isTheOneRegisteredRemotely(): Boolean =
        request("registration_status", JSONObject())
            .optBoolean("the_one_registered", false)

    fun heartbeat(context: Context): Boolean {
        val payload = JSONObject()
            .put("device_id", deviceId(context))
            .put("name", deviceName())
            .put("person_name", personName(context))
            .put("platform", "Android ${Build.VERSION.RELEASE}")
            .put("version", BuildConfig.VERSION_CODE.toString())

        val json = request("heartbeat", payload)
        val blocked = json.optBoolean("blocked", false)
        val owner = json.optBoolean("owner", false)
        val musicRights = json.optBoolean("music_rights", owner)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BLOCKED, blocked)
            .putBoolean(KEY_OWNER, owner)
            .putBoolean(KEY_MUSIC_RIGHTS, musicRights)
            .apply()
        return blocked
    }

    fun isOwnerEligible(): Boolean =
        Build.MODEL.equals("SM-S931B", ignoreCase = true)

    fun claimOwner(context: Context, pin: String): Boolean {
        if (!isOwnerEligible()) return false
        val json = request(
            "claim_owner",
            JSONObject()
                .put("pin", pin)
                .put("request_device_id", deviceId(context))
                .put("request_model", Build.MODEL)
        )
        val owner = json.optBoolean("owner", false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OWNER, owner)
            .apply()
        return owner
    }

    fun claimInitialOwner(context: Context): Boolean {
        if (!isOwnerEligible()) return false
        return runCatching { claimOwner(context, "290114") }.getOrDefault(false)
    }

    fun ownerStatus(context: Context, pin: String): Boolean {
        val json = request(
            "owner_status",
            JSONObject()
                .put("pin", pin)
                .put("request_device_id", deviceId(context))
        )
        val owner = json.optBoolean("owner", false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OWNER, owner)
            .apply()
        return owner
    }

    fun recoverOwner(context: Context, pin: String, recoveryCode: String): Boolean {
        val json = request(
            "recover_owner",
            JSONObject()
                .put("pin", pin)
                .put("request_device_id", deviceId(context))
                .put("recovery_code", recoveryCode.trim().uppercase())
        )
        val owner = json.optBoolean("owner", false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OWNER, owner)
            .apply()
        return owner
    }

    fun listDevices(context: Context, pin: String): List<MainRegisteredDevice> {
        val json = request(
            "list",
            JSONObject()
                .put("pin", pin)
                .put("request_device_id", deviceId(context))
        )
        val array = json.optJSONArray("devices") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    MainRegisteredDevice(
                        id = item.optString("device_id"),
                        name = item.optString("name", "Apparaat"),
                        personName = item.optString("person_name", "").trim(),
                        platform = item.optString("platform", ""),
                        version = item.optString("version", ""),
                        blocked = item.optBoolean("blocked", false),
                        owner = item.optBoolean("owner", false),
                        musicRights = item.optBoolean("music_rights", item.optBoolean("owner", false)),
                        online = item.optBoolean("online", false),
                        lastSeen = item.optLong("last_seen", 0L)
                    )
                )
            }
        }
    }

    fun windowsConnectionStatus(context: Context): List<WindowsConnectionDevice> {
        val json = request(
            "connection_status",
            JSONObject().put("request_device_id", deviceId(context))
        )
        val array = json.optJSONArray("devices") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    WindowsConnectionDevice(
                        id = item.optString("device_id", ""),
                        name = item.optString("name", "Windows apparaat"),
                        online = item.optBoolean("online", false),
                        lastSeen = item.optLong("last_seen", 0L)
                    )
                )
            }
        }
    }

    fun refreshMusicRights(context: Context): Boolean {
        val json = request(
            "music_access",
            JSONObject().put("device_id", deviceId(context))
        )
        val allowed = json.optBoolean("allowed", false)
        val owner = json.optBoolean("owner", false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MUSIC_RIGHTS, allowed)
            .putBoolean(KEY_OWNER, owner)
            .apply()
        return allowed
    }

    fun setMusicRights(
        context: Context,
        pin: String,
        targetDeviceId: String,
        enabled: Boolean
    ) {
        request(
            "set_music_rights",
            JSONObject()
                .put("pin", pin)
                .put("request_device_id", deviceId(context))
                .put("device_id", targetDeviceId)
                .put("enabled", enabled)
        )
    }

    fun setBlocked(context: Context, pin: String, targetDeviceId: String, blocked: Boolean) {
        request(
            "set_blocked",
            JSONObject()
                .put("pin", pin)
                .put("request_device_id", deviceId(context))
                .put("device_id", targetDeviceId)
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
