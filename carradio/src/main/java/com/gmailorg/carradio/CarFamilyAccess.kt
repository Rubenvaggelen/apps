package com.gmailorg.carradio

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object CarFamilyAccess {
    private const val ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/devices.php"
    private const val PREFS = "car_family_access"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MUSIC_RIGHTS = "music_rights"
    private const val KEY_PERSON_NAME = "person_name"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun personName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PERSON_NAME, "")
            .orEmpty()
            .trim()

    fun hasPersonName(context: Context): Boolean =
        personName(context).isNotBlank()

    fun savePersonName(context: Context, personName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERSON_NAME, personName.trim())
            .apply()
    }

    fun hasCachedMusicRights(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MUSIC_RIGHTS, false)

    fun refreshMusicRights(context: Context): Boolean {
        heartbeat(context)
        val json = request(
            "music_access",
            JSONObject().put("device_id", deviceId(context))
        )
        val allowed = json.optBoolean("allowed", false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MUSIC_RIGHTS, allowed)
            .apply()
        return allowed
    }

    fun heartbeat(context: Context) {
        val json = request(
            "heartbeat",
            JSONObject()
                .put("device_id", deviceId(context))
                .put("name", "The One Car")
                .put("person_name", personName(context))
                .put("device_role", "car")
                .put("platform", "Car Android " + Build.VERSION.RELEASE)
                .put("version", BuildConfig.VERSION_CODE.toString())
        )
        val allowed = json.optBoolean("music_rights", false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MUSIC_RIGHTS, allowed)
            .apply()
    }

    private fun request(action: String, body: JSONObject): JSONObject {
        val connection =
            (URL("$ENDPOINT?action=$action").openConnection() as HttpURLConnection).apply {
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
        val stream =
            if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()

        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (code !in 200..299 || !json.optBoolean("ok", false)) {
            throw IllegalStateException(json.optString("error", "Serverfout $code"))
        }
        return json
    }
}
