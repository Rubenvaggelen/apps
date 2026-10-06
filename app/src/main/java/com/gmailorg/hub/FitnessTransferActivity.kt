package com.gmailorg.hub
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** Explicit result transfer; no files, credentials or unrelated preferences. */
class FitnessTransferActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (caller != "com.gmailorg.runcoach" ||
            packageManager.checkSignatures(packageName, caller) != PackageManager.SIGNATURE_MATCH) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val preferences = getSharedPreferences("fitness_progress", MODE_PRIVATE)
        val raw = FitnessTransferCodec.encode(preferences.all)
        if (raw.length > 256000) setResult(RESULT_CANCELED)
        else setResult(RESULT_OK, Intent().putExtra("fitness_data", raw))
        finish()
    }
}
