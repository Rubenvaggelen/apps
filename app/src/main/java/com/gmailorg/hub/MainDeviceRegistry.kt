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
    val mediaPlayerRights: Boolean,
    val mixesRights: Boolean,
    val sharedRights: Boolean,
    val favoritesRights: Boolean,
    val djRights: Boolean,
    val runRights: Boolean,
    val downloadsRights: Boolean,
    val fileDownloadsRights: Boolean,
    val pendingMediaPlayer: Boolean,
    val pendingMixes: Boolean,
    val pendingShared: Boolean,
    val pendingFavorites: Boolean,
    val pendingDj: Boolean,
    val pendingRun: Boolean,
    val pendingDownloads: Boolean,
    val pendingFileDownloads: Boolean,
    val online: Boolean,
    val lastSeen: Long
)

data class MainAccessStatus(
    val allowed: Boolean,
    val pending: Boolean,
    val owner: Boolean,
    val scope: String
)

data class WindowsConnectionDevice(
    val id: String,
    val name: String,
    val online: Boolean,
    val lastSeen: Long
)

data class MainPendingAccessRequest(
    val deviceId: String,
    val personName: String,
    val deviceName: String,
    val scope: String,
    val requestedAt: String
)


object MainDeviceRegistry {
    const val ACCESS_MEDIA_PLAYER = "media_player"
    const val ACCESS_MIXES = "mixes"
    const val ACCESS_SHARED = "shared"
    const val ACCESS_FAVORITES = "favorites"
    const val ACCESS_DJ = "dj"
    const val ACCESS_RUN = "run"
    // Legacy scope "downloads" is the existing right to organize/move music.
    const val ACCESS_ORGANIZE = "downloads"
    // Separate explicit right for saving a Shared Media file onto the device.
    const val ACCESS_FILE_DOWNLOADS = "download_files"
    @Deprecated("Use ACCESS_ORGANIZE or ACCESS_FILE_DOWNLOADS explicitly")
    const val ACCESS_DOWNLOADS = ACCESS_ORGANIZE

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

    fun hasAccess(context: Context, scope: String): Boolean =
        isLocallyOwner(context) ||
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("access_" + scope, false)

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
        val accessRights = json.optJSONObject("access_rights") ?: JSONObject()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BLOCKED, blocked)
            .putBoolean(KEY_OWNER, owner)
            .putBoolean(KEY_MUSIC_RIGHTS, musicRights)
            .putBoolean("access_media_player", accessRights.optBoolean(ACCESS_MEDIA_PLAYER, owner))
            .putBoolean("access_mixes", accessRights.optBoolean(ACCESS_MIXES, owner || musicRights))
            .putBoolean("access_shared", accessRights.optBoolean(ACCESS_SHARED, owner || musicRights))
            .putBoolean("access_favorites", accessRights.optBoolean(ACCESS_FAVORITES, owner || musicRights))
            .putBoolean("access_dj", accessRights.optBoolean(ACCESS_DJ, owner))
            .putBoolean("access_run", accessRights.optBoolean(ACCESS_RUN, owner))
            .putBoolean("access_downloads", accessRights.optBoolean(ACCESS_ORGANIZE, owner))
            .putBoolean("access_download_files", accessRights.optBoolean(ACCESS_FILE_DOWNLOADS, owner))
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
                        mediaPlayerRights = item.optJSONObject("access_rights")
                            ?.optBoolean(ACCESS_MEDIA_PLAYER, item.optBoolean("owner", false))
                            ?: item.optBoolean("owner", false),
                        mixesRights = item.optJSONObject("access_rights")
                            ?.optBoolean(ACCESS_MIXES, item.optBoolean("music_rights", false))
                            ?: item.optBoolean("music_rights", item.optBoolean("owner", false)),
                        sharedRights = item.optJSONObject("access_rights")
                            ?.optBoolean(ACCESS_SHARED, item.optBoolean("music_rights", false))
                            ?: item.optBoolean("music_rights", item.optBoolean("owner", false)),
                        favoritesRights = item.optJSONObject("access_rights")
                            ?.optBoolean(ACCESS_FAVORITES, item.optBoolean("music_rights", false))
                            ?: item.optBoolean("music_rights", item.optBoolean("owner", false)),
                        djRights = item.optJSONObject("access_rights")
                            ?.optBoolean(ACCESS_DJ, item.optBoolean("owner", false))
                            ?: item.optBoolean("owner", false),
                        runRights = item.optJSONObject("access_rights")
                            ?.optBoolean(ACCESS_RUN, item.optBoolean("owner", false))
                            ?: item.optBoolean("owner", false),
                        downloadsRights = item.optJSONObject("access_rights")
                            ?.optBoolean(ACCESS_ORGANIZE, item.optBoolean("owner", false))
                            ?: item.optBoolean("owner", false),
                        fileDownloadsRights = item.optJSONObject("access_rights")
                            ?.optBoolean(ACCESS_FILE_DOWNLOADS, item.optBoolean("owner", false))
                            ?: item.optBoolean("owner", false),
                        pendingMediaPlayer = item.optJSONObject("access_requests")?.has(ACCESS_MEDIA_PLAYER) == true,
                        pendingMixes = item.optJSONObject("access_requests")?.has(ACCESS_MIXES) == true,
                        pendingShared = item.optJSONObject("access_requests")?.has(ACCESS_SHARED) == true,
                        pendingFavorites = item.optJSONObject("access_requests")?.has(ACCESS_FAVORITES) == true,
                        pendingDj = item.optJSONObject("access_requests")?.has(ACCESS_DJ) == true,
                        pendingRun = item.optJSONObject("access_requests")?.has(ACCESS_RUN) == true,
                        pendingDownloads = item.optJSONObject("access_requests")?.has(ACCESS_ORGANIZE) == true,
                        pendingFileDownloads = item.optJSONObject("access_requests")?.has(ACCESS_FILE_DOWNLOADS) == true,
                        online = item.optBoolean("online", false),
                        lastSeen = item.optLong("last_seen", 0L)
                    )
                )
            }
        }
    }

    fun pendingAccessRequests(context: Context): List<MainPendingAccessRequest> {
        if (!isLocallyOwner(context)) return emptyList()
        val json = request(
            "pending_requests",
            JSONObject().put("request_device_id", deviceId(context))
        )
        val array = json.optJSONArray("requests") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    MainPendingAccessRequest(
                        deviceId = item.optString("device_id", ""),
                        personName = item.optString("person_name", "").trim(),
                        deviceName = item.optString("device_name", "").trim(),
                        scope = item.optString("scope", "").trim(),
                        requestedAt = item.optString("requested_at", "").trim()
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

    fun refreshAccess(context: Context, scope: String): MainAccessStatus {
        val json = request(
            "access_status",
            JSONObject()
                .put("device_id", deviceId(context))
                .put("scope", scope)
        )
        val status = MainAccessStatus(
            allowed = json.optBoolean("allowed", false),
            pending = json.optBoolean("pending", false),
            owner = json.optBoolean("owner", false),
            scope = json.optString("scope", scope)
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OWNER, status.owner)
            .putBoolean("access_" + scope, status.allowed)
            .apply()
        return status
    }

    fun requestAccess(context: Context, scope: String): MainAccessStatus {
        val json = request(
            "request_access",
            JSONObject()
                .put("device_id", deviceId(context))
                .put("scope", scope)
        )
        return MainAccessStatus(
            allowed = json.optBoolean("allowed", false),
            pending = json.optBoolean("pending", false),
            owner = isLocallyOwner(context),
            scope = json.optString("scope", scope)
        )
    }

    fun setAccessRight(
        context: Context,
        pin: String,
        targetDeviceId: String,
        scope: String,
        enabled: Boolean
    ) {
        synchronized(AccessRequestNotifications.syncLock) {
        request(
            "set_access_right",
            JSONObject()
                .put("pin", pin)
                .put("request_device_id", deviceId(context))
                .put("device_id", targetDeviceId)
                .put("scope", scope)
                .put("enabled", enabled)
        )
        AccessRequestNotifications.resolve(context, targetDeviceId, scope)
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
