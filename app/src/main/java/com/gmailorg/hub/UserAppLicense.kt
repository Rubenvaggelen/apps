package com.gmailorg.hub

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * An independent, signed installation. The user APK never claims Main ownership.
 * Owner approves THE_ONE_USER on the exact public-key-derived device ID.
 */
internal object UserAppLicense {
    private const val ALIAS = "the-one-user-install-p256-v1"
    private const val ENDPOINT = "https://rubenvanaggelen.com/the-one-remote-api/devices.php?action="
    private const val PACKAGE_NAME = "com.gmailorg.theoneuser"
    private const val SCOPE = "the_one_user"

    data class Status(val allowed: Boolean, val pending: Boolean)

    private fun keyPair(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
        val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    fun installationId(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyPair().public.encoded)
        return "fam-" + digest.take(24).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun signedPayload(action: String, keys: KeyPair, id: String): JSONObject {
        val nonceBytes = ByteArray(32)
        SecureRandom().nextBytes(nonceBytes)
        val nonce = b64(nonceBytes)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keys.private)
        signature.update("$action|$id|$SCOPE|$nonce".toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("package", PACKAGE_NAME)
            .put("device_id", id)
            .put("nonce", nonce)
            .put("signature", b64(signature.sign()))
    }

    private fun post(action: String, body: JSONObject): JSONObject {
        val conn = URL(ENDPOINT + action).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (code !in 200..299 || !result.optBoolean("ok")) {
                throw IllegalStateException(result.optString("error", "Serverfout $code"))
            }
            return result
        } finally {
            conn.disconnect()
        }
    }

    fun registerAndCheck(name: String): Status {
        val cleaned = name.trim().replace(Regex("\\s+"), " ")
        require(cleaned.length in 2..80 && cleaned.none { Character.isISOControl(it) }) {
            "Vul een geldige naam van 2 tot 80 tekens in."
        }
        val keys = keyPair()
        val id = installationId()
        val register = signedPayload("register", keys, id)
            .put("public_key", b64(keys.public.encoded))
            .put("person_name", cleaned)
            .put("platform", "Android " + Build.VERSION.RELEASE)
            .put("device_name", Build.MANUFACTURER + " " + Build.MODEL)
        post("family_register", register)
        val status = post("family_check", signedPayload("check", keys, id))
        return Status(
            allowed = status.optBoolean("allowed", false),
            pending = status.optBoolean("pending", false)
        )
    }
    fun checkAccess(): Status {
        val keys = keyPair()
        val id = installationId()
        val response = post("family_check", signedPayload("check", keys, id))
        return Status(response.optBoolean("allowed", false), response.optBoolean("pending", false))
    }

    /**
     * Revalidate the signed installation before loading fine-grained rights.
     * No app-level permission is inherited from another device with the same name.
     */
    fun syncRights(context: android.content.Context): Boolean {
        val status = checkAccess()
        val scopes = listOf(
            MainDeviceRegistry.ACCESS_MEDIA_PLAYER,
            MainDeviceRegistry.ACCESS_MIXES,
            MainDeviceRegistry.ACCESS_SHARED,
            MainDeviceRegistry.ACCESS_FAVORITES,
            MainDeviceRegistry.ACCESS_DJ,
            MainDeviceRegistry.ACCESS_RUN,
            MainDeviceRegistry.ACCESS_CINEMA,
            MainDeviceRegistry.ACCESS_SERIES,
            MainDeviceRegistry.ACCESS_TV,
            MainDeviceRegistry.ACCESS_ORGANIZE,
            MainDeviceRegistry.ACCESS_FILE_DOWNLOADS
        )
        val rights = mutableMapOf<String, Boolean>()
        for (scope in scopes) {
            rights[scope] = if (status.allowed) {
                val reply = post("access_status",
                    JSONObject().put("device_id", installationId()).put("scope", scope))
                reply.optBoolean("allowed", false)
            } else false
        }
        val prefs = context.getSharedPreferences("main_device_registry", android.content.Context.MODE_PRIVATE)
        val edit = prefs.edit()
            .putBoolean("owner", false)
            .putBoolean("blocked", !status.allowed)
            .putBoolean("music_rights", status.allowed && (
                rights[MainDeviceRegistry.ACCESS_SHARED] == true ||
                rights[MainDeviceRegistry.ACCESS_MIXES] == true))
        rights.forEach { (scope, allowed) -> edit.putBoolean("access_" + scope, allowed) }
        edit.apply()
        return !status.allowed
    }

}
