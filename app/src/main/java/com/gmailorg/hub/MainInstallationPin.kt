package com.gmailorg.hub

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * An installation-local settings PIN. It cannot be restored through Android backup
 * and the shared temporary 1306 PIN never unlocks settings.
 * Existing installations retain settings but need a personal PIN on their first opening after update.
 */
object MainInstallationPin {
    const val TEMPORARY_PIN = "1306"
    private const val FILE_NAME = "main-personal-pin-v1.json"
    private const val ROUNDS = 160_000
    private val random = SecureRandom()

    private fun pinFile(context: Context): File =
        File(context.noBackupFilesDir, FILE_NAME)

    fun hasPersonalPin(context: Context): Boolean {
        val obj = runCatching { JSONObject(pinFile(context).readText()) }.getOrNull()
        return obj?.optString("salt", "").orEmpty().isNotEmpty() &&
            obj?.optString("hash", "").orEmpty().isNotEmpty()
    }

    fun mustChoosePersonalPin(context: Context): Boolean =
        !hasPersonalPin(context)

    private fun digest(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, ROUNDS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun verify(context: Context, pin: String): Boolean {
        if (!pin.matches(Regex("^[0-9]{4,8}$"))) return false
        val obj = runCatching { JSONObject(pinFile(context).readText()) }.getOrNull() ?: return false
        val salt = runCatching { Base64.decode(obj.getString("salt"), Base64.NO_WRAP) }.getOrNull()
            ?: return false
        val expected = runCatching { Base64.decode(obj.getString("hash"), Base64.NO_WRAP) }.getOrNull()
            ?: return false
        return MessageDigest.isEqual(expected, digest(pin, salt))
    }

    fun changePersonalPin(context: Context, oldPin: String, newPin: String, confirmation: String) {
        require(hasPersonalPin(context)) { "Stel eerst een persoonlijke PIN in." }
        require(verify(context, oldPin)) { "De huidige PIN is niet correct" }
        validateNewPin(newPin, confirmation)
        savePin(context, newPin)
    }

    private fun validateNewPin(newPin: String, confirmation: String) {
        require(newPin.matches(Regex("^[0-9]{4,8}$"))) { "Kies 4 tot 8 cijfers" }
        require(newPin != TEMPORARY_PIN && newPin != "290114") { "Kies een andere PIN dan de standaardcode" }
        require(newPin == confirmation) { "De twee nieuwe PIN-codes zijn niet gelijk" }
    }

    private fun savePin(context: Context, newPin: String) {
        val salt = ByteArray(16).also(random::nextBytes)
        val encoded = JSONObject()
            .put("version", 1)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("hash", Base64.encodeToString(digest(newPin, salt), Base64.NO_WRAP))
            .toString()
        val file = pinFile(context)
        val staging = File(file.parentFile, "$FILE_NAME.new")
        staging.writeText(encoded)
        require(staging.renameTo(file)) { "PIN opslaan is mislukt; probeer opnieuw" }
    }

    fun createPersonalPin(context: Context, temporaryPin: String, newPin: String, confirmation: String) {
        require(!hasPersonalPin(context)) { "Er is al een persoonlijke PIN ingesteld" }
        require(temporaryPin == TEMPORARY_PIN) { "De tijdelijke PIN is niet correct" }
        validateNewPin(newPin, confirmation)
        savePin(context, newPin)
    }
}
