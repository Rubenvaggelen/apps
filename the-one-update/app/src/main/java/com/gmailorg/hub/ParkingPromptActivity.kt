package com.gmailorg.hub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Wordt geopend vanuit een The One-parkeermelding en vraagt eerst toestemming
 * voordat de officiële Amsterdam App wordt geopend.
 */
class ParkingPromptActivity : AppCompatActivity() {
    companion object {
        private const val AMSTERDAM_PACKAGE = "nl.amsterdam.app"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle("Parkeren in Amsterdam")
            .setMessage("Wil je de Amsterdam App van Gemeente Amsterdam openen om Aanmelden parkeren te gebruiken?")
            .setPositiveButton("Ja, openen") { _, _ ->
                openAmsterdamApp()
                finish()
            }
            .setNegativeButton("Nee") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openAmsterdamApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(AMSTERDAM_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            return
        }

        // Als de officiële app nog niet is geïnstalleerd, open direct de Play Store.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$AMSTERDAM_PACKAGE")))
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$AMSTERDAM_PACKAGE")
                )
            )
        }
    }
}
