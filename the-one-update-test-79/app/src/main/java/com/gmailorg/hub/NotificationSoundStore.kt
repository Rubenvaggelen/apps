package com.gmailorg.hub

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings

/**
 * Bewaart welk geluid gebruikt moet worden voor alle meldingen van de app
 * (supermarkt + parkeren). Omdat een Android-notificatiekanaal zijn geluid
 * niet meer kan wijzigen ná aanmaak, houden we een "versienummer" bij: zodra
 * je een nieuw geluid kiest, maken we een vers kanaal aan met dat geluid.
 */
object NotificationSoundStore {

    private const val PREFS = "notification_sound_prefs"
    private const val KEY_URI = "sound_uri"
    private const val KEY_VERSION = "sound_version"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Null betekent: standaard systeem-meldingsgeluid. */
    fun getSoundUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_URI, null) ?: return null
        return Uri.parse(raw)
    }

    fun setSoundUri(context: Context, uri: Uri?) {
        val current = getVersion(context)
        prefs(context).edit()
            .putString(KEY_URI, uri?.toString())
            .putInt(KEY_VERSION, current + 1)
            .apply()
    }

    fun getVersion(context: Context): Int = prefs(context).getInt(KEY_VERSION, 0)

    fun getSoundDisplayName(context: Context): String {
        val uri = getSoundUri(context) ?: RingtoneManager.getActualDefaultRingtoneUri(
            context, RingtoneManager.TYPE_NOTIFICATION
        ) ?: return "Standaard"
        return try {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Standaard"
        } catch (e: Exception) {
            "Standaard"
        }
    }

    fun effectiveUri(context: Context): Uri =
        getSoundUri(context) ?: Settings.System.DEFAULT_NOTIFICATION_URI
}
