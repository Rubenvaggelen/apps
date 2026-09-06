package com.gmailorg.hub

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Deze activity wordt door de browser geopend zodra Google je terugstuurt na
 * het goedkeuringsscherm (via de custom URI-scheme redirect). Wisselt de
 * meegekregen code in voor tokens en gaat terug naar het Huishouden-scherm.
 */
class GoogleOAuthRedirectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri != null) {
            Thread {
                val success = GoogleTasksAuth.handleRedirect(applicationContext, uri)
                if (success) {
                    GoogleTasksSyncWorker.schedule(applicationContext)
                    GoogleTasksSync.syncNow(applicationContext)
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (success) "Gekoppeld aan Google Tasks" else "Koppelen mislukt",
                        Toast.LENGTH_SHORT
                    ).show()
                    startActivity(
                        Intent(this, HouseholdActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                    finish()
                }
            }.start()
        } else {
            finish()
        }
    }
}
