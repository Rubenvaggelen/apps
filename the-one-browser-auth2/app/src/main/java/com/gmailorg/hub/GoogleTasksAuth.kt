package com.gmailorg.hub

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Regelt de koppeling met Google Tasks via een directe, browser-gebaseerde
 * OAuth-flow (PKCE) met de bestaande Android-OAuth-client. Dit omzeilt de
 * Play Services "Identity/Authorization"-bibliotheek, die op sommige
 * toestellen het goedkeuringsscherm meteen zelf afsluit zonder dat de
 * gebruiker iets kan doen.
 */
object GoogleTasksAuth {

    private const val TAG = "GoogleTasksAuth"

    // De Android-OAuth-client uit Google Cloud Console (project the-one-507817).
    private const val CLIENT_ID = "581108128983-olfh4oueiq2d4tdkf0p2a3tgahij2548.apps.googleusercontent.com"
    private const val REDIRECT_URI = "com.googleusercontent.apps.581108128983-olfh4oueiq2d4tdkf0p2a3tgahij2548:/oauth2redirect"
    const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"

    private const val PREFS = "google_tasks_auth"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_ACCESS_TOKEN_EXPIRY = "access_token_expiry"
    private const val KEY_PENDING_VERIFIER = "pending_code_verifier"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLinked(context: Context): Boolean = prefs(context).getString(KEY_REFRESH_TOKEN, null) != null

    fun unlink(context: Context) {
        prefs(context).edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_TOKEN_EXPIRY)
            .apply()
    }

    /** Opent de browser (Custom Tab) om toestemming te vragen. */
    fun startAuthorization(context: Context) {
        val verifier = randomUrlSafeString(64)
        val challenge = codeChallenge(verifier)
        prefs(context).edit().putString(KEY_PENDING_VERIFIER, verifier).apply()

        val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=${URLEncoder.encode(CLIENT_ID, "UTF-8")}" +
            "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}" +
            "&response_type=code" +
            "&scope=${URLEncoder.encode(TASKS_SCOPE, "UTF-8")}" +
            "&access_type=offline" +
            "&prompt=consent" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256"

        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
    }

    /**
     * Wordt aangeroepen door GoogleOAuthRedirectActivity zodra de browser
     * teruggeleid is. Wisselt de code in voor tokens.
     * @return true bij succes.
     */
    fun handleRedirect(context: Context, redirectUri: Uri): Boolean {
        val code = redirectUri.getQueryParameter("code") ?: return false
        val verifier = prefs(context).getString(KEY_PENDING_VERIFIER, null) ?: return false

        return try {
            val body = "code=${URLEncoder.encode(code, "UTF-8")}" +
                "&client_id=${URLEncoder.encode(CLIENT_ID, "UTF-8")}" +
                "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}" +
                "&grant_type=authorization_code" +
                "&code_verifier=$verifier"

            val response = post("https://oauth2.googleapis.com/token", body)
            val json = JSONObject(response)
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token", null)
            val expiresIn = json.optLong("expires_in", 3600)

            val editor = prefs(context).edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putLong(KEY_ACCESS_TOKEN_EXPIRY, System.currentTimeMillis() + (expiresIn * 1000))
                .remove(KEY_PENDING_VERIFIER)
            if (refreshToken != null) {
                editor.putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            editor.apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Code inwisselen voor tokens mislukt", e)
            false
        }
    }

    /**
     * Haalt (blocking!) een geldig access token op, en vernieuwt 'm indien
     * verlopen via het refresh token. Moet op een achtergrondthread
     * aangeroepen worden, nooit op de main thread.
     */
    fun getAccessTokenBlocking(context: Context): String? {
        val cachedToken = prefs(context).getString(KEY_ACCESS_TOKEN, null)
        val expiry = prefs(context).getLong(KEY_ACCESS_TOKEN_EXPIRY, 0)
        // 60 seconden marge om verlopen-tijdens-gebruik te voorkomen.
        if (cachedToken != null && System.currentTimeMillis() < expiry - 60_000) {
            return cachedToken
        }

        val refreshToken = prefs(context).getString(KEY_REFRESH_TOKEN, null) ?: return null
        return try {
            val body = "client_id=${URLEncoder.encode(CLIENT_ID, "UTF-8")}" +
                "&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}" +
                "&grant_type=refresh_token"
            val response = post("https://oauth2.googleapis.com/token", body)
            val json = JSONObject(response)
            val accessToken = json.getString("access_token")
            val expiresIn = json.optLong("expires_in", 3600)
            prefs(context).edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putLong(KEY_ACCESS_TOKEN_EXPIRY, System.currentTimeMillis() + (expiresIn * 1000))
                .apply()
            accessToken
        } catch (e: Exception) {
            Log.e(TAG, "Access token vernieuwen mislukt", e)
            null
        }
    }

    private fun randomUrlSafeString(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun post(urlString: String, body: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.outputStream.use { it.write(body.toByteArray()) }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
