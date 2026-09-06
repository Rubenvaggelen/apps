package com.gmailorg.hub

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

/**
 * Regelt de koppeling met een Google-account voor toegang tot Google Tasks
 * (zodat items die je met "Hey Google, voeg ... toe" of "herinner me ..."
 * aanmaakt, automatisch op de boodschappenlijst komen).
 */
object GoogleTasksAuth {

    private const val TAG = "GoogleTasksAuth"
    private const val PREFS = "google_tasks_auth"
    private const val KEY_ACCOUNT_EMAIL = "account_email"

    // Read-only volstaat: we lezen taken uit en zetten ze zelf op "voltooid"
    // zodra ze op de boodschappenlijst staan, dat mag ook met deze scope.
    const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun linkedAccountEmail(context: Context): String? = prefs(context).getString(KEY_ACCOUNT_EMAIL, null)

    fun isLinked(context: Context): Boolean = linkedAccountEmail(context) != null

    fun unlink(context: Context) {
        prefs(context).edit().remove(KEY_ACCOUNT_EMAIL).apply()
    }

    private fun signInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(TASKS_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    /** Start het Google-inlogscherm; verwerk het resultaat in onActivityResult/registerForActivityResult. */
    fun getSignInIntent(activity: Activity): Intent = signInClient(activity).signInIntent

    sealed class SignInResult {
        data class Success(val email: String) : SignInResult()
        data class Failure(val code: Int, val exceptionClass: String, val message: String) : SignInResult()
    }

    /** @return het resultaat van de inlogpoging, inclusief foutcode bij mislukking. */
    fun handleSignInResult(context: Context, data: Intent?): SignInResult {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).result
            val email = account?.email
            if (email != null) {
                prefs(context).edit().putString(KEY_ACCOUNT_EMAIL, email).apply()
                SignInResult.Success(email)
            } else {
                SignInResult.Failure(-1, "geen account", "Geen e-mailadres ontvangen")
            }
        } catch (e: com.google.android.gms.common.api.ApiException) {
            Log.e(TAG, "Inloggen mislukt: code ${e.statusCode}", e)
            SignInResult.Failure(e.statusCode, "ApiException", e.message ?: e.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Inloggen mislukt", e)
            SignInResult.Failure(-2, e.javaClass.simpleName, e.message ?: e.toString())
        }
    }

    /**
     * Haalt (blocking!) een geldig access token op voor het gekoppelde account.
     * Moet op een achtergrondthread aangeroepen worden, nooit op de main thread.
     */
    fun getAccessTokenBlocking(context: Context): String? {
        val email = linkedAccountEmail(context) ?: return null
        return try {
            val account = Account(email, "com.google")
            GoogleAuthUtil.getToken(context, account, "oauth2:$TASKS_SCOPE")
        } catch (e: Exception) {
            Log.e(TAG, "Access token ophalen mislukt", e)
            null
        }
    }
}
