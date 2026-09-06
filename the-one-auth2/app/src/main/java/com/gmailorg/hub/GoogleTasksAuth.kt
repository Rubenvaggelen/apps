package com.gmailorg.hub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks

/**
 * Regelt de koppeling met een Google-account voor toegang tot Google Tasks,
 * via Google's huidige "Authorization API" (de opvolger van de oudere
 * GoogleSignIn-scopes-aanpak, die bij nieuwe OAuth-clients op het Google
 * Auth Platform niet altijd meer goed werkt).
 */
object GoogleTasksAuth {

    private const val TAG = "GoogleTasksAuth"
    private const val PREFS = "google_tasks_auth"
    private const val KEY_LINKED = "linked"

    const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLinked(context: Context): Boolean = prefs(context).getBoolean(KEY_LINKED, false)

    fun unlink(context: Context) {
        prefs(context).edit().putBoolean(KEY_LINKED, false).apply()
    }

    private fun markLinked(context: Context) {
        prefs(context).edit().putBoolean(KEY_LINKED, true).apply()
    }

    private fun buildRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(TASKS_SCOPE)))
            .build()

    /**
     * Start de autorisatie. Als Google direct akkoord kan gaan (bv. omdat er
     * al eerder toestemming gegeven is) komt [onDone] meteen met succes.
     * Anders moet de gebruiker nog een goedkeuringsscherm zien: dan wordt
     * [onNeedsUserAction] aangeroepen met een IntentSenderRequest die je met
     * ActivityResultContracts.StartIntentSenderForResult moet afhandelen.
     */
    fun requestAuthorization(
        activity: Activity,
        onNeedsUserAction: (IntentSenderRequest) -> Unit,
        onDone: (success: Boolean, errorMessage: String?) -> Unit
    ) {
        Identity.getAuthorizationClient(activity)
            .authorize(buildRequest())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        onNeedsUserAction(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    } else {
                        onDone(false, "Geen goedkeuringsscherm beschikbaar")
                    }
                } else {
                    markLinked(activity)
                    onDone(true, null)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Autorisatie mislukt", e)
                onDone(false, "${e.javaClass.simpleName}: ${e.message}")
            }
    }

    /** Verwerkt het resultaat van het goedkeuringsscherm (na onNeedsUserAction). */
    fun handleAuthorizationResult(context: Context, data: Intent?): Boolean {
        return try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            markLinked(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Verwerken van goedkeuring mislukt", e)
            false
        }
    }

    /**
     * Haalt (blocking!) een geldig access token op. Werkt ook stilletjes op de
     * achtergrond zodra er al eenmaal toestemming gegeven is. Moet op een
     * achtergrondthread aangeroepen worden, nooit op de main thread.
     */
    fun getAccessTokenBlocking(context: Context): String? {
        if (!isLinked(context)) return null
        return try {
            val result: AuthorizationResult = Tasks.await(
                Identity.getAuthorizationClient(context).authorize(buildRequest())
            )
            result.accessToken
        } catch (e: Exception) {
            Log.e(TAG, "Access token ophalen mislukt", e)
            null
        }
    }
}
