package com.gmailorg.runcoach

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object RunAccessRegistry {
    private const val ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/devices.php"
    private fun prefs(c: Context) = c.getSharedPreferences("run_access", Context.MODE_PRIVATE)
    fun name(c: Context) = prefs(c).getString("name", "").orEmpty()
    fun saveName(c: Context, name: String) { prefs(c).edit().putString("name", name.trim()).apply() }
    fun bindFromMain(activity: android.app.Activity) {
        val caller = activity.callingPackage ?: return
        if (caller != "com.gmailorg.hub" ||
            activity.packageManager.checkSignatures(caller, activity.packageName) != PackageManager.SIGNATURE_MATCH) return
        val id = activity.intent.getStringExtra("the_one_main_device_id").orEmpty()
        if (id.isBlank()) return
        prefs(activity).edit().putString("device_id", id).putBoolean("allowed", false).apply()
    }
    private fun id(c: Context): String {
        val p = prefs(c)
        val old = p.getString("device_id", "").orEmpty()
        if (old.isNotBlank()) return old
        return UUID.randomUUID().toString().also { p.edit().putString("device_id", it).apply() }
    }
    fun allowed(c: Context) = name(c).isNotBlank() && prefs(c).getBoolean("allowed", false)
    data class Status(val allowed: Boolean, val pending: Boolean, val blocked: Boolean)
    fun check(c: Context, ask: Boolean): Status {
        val device = id(c)
        val beat = post("heartbeat", JSONObject()
            .put("device_id", device)
            .put("person_name", name(c))
            .put("name", listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" "))
            .put("platform", "Android ${Build.VERSION.RELEASE}")
            .put("version", BuildConfig.VERSION_CODE.toString()))
        val blocked = beat.optBoolean("blocked", true)
        if (blocked) {
            prefs(c).edit().putBoolean("allowed", false).apply()
            return Status(false, false, true)
        }
        val payload = JSONObject().put("device_id", device).put("scope", "run")
        var result = post("access_status", payload)
        if (ask && !result.optBoolean("allowed", false)) result = post("request_access", payload)
        val allowed = result.optBoolean("allowed", false)
        prefs(c).edit().putBoolean("allowed", allowed).apply()
        return Status(allowed, result.optBoolean("pending", false), false)
    }
    private fun post(action: String, body: JSONObject): JSONObject {
        val connection = URL("$ENDPOINT?action=$action").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(raw)
            kotlin.check(code in 200..299 && json.optBoolean("ok", false)) { "Toegang controleren mislukt" }
            return json
        } finally { connection.disconnect() }
    }
}
