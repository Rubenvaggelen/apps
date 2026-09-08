package com.gmailorg.hub

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Vangt meldingen op van ALLE apps op het toestel (WhatsApp, Instagram,
 * Messenger, Facebook, Telegram, X, Gmail, sms, enz.) — dit vereist dat de
 * gebruiker de app handmatig aanzet bij Instellingen > Apps > Speciale
 * toegang > Meldingtoegang, Android staat dit niet automatisch toe.
 *
 * Deze service leest alleen wat er al als systeemmelding verschijnt; er is
 * geen toegang tot volledige chatgeschiedenis of social-media-feeds, omdat
 * die apps geen publieke API's bieden voor persoonlijk gebruik.
 */
class UnifiedNotificationListener : NotificationListenerService() {

    // Bewaar de originele PendingIntent + RemoteInput per meldingssleutel,
    // zodat de UI later een antwoord kan versturen zonder de bron-app te openen.
    companion object {
        private val replyActions = mutableMapOf<String, Pair<PendingIntent, RemoteInput>>()

        // PendingIntent.send() heeft een Context nodig; de service zet deze
        // hieronder bij het opstarten zodat sendReply() hem kan gebruiken.
        private var appContext: android.content.Context? = null

        fun sendReply(key: String, text: String): Boolean {
            val pair = replyActions[key] ?: return false
            val context = appContext ?: return false
            val (pendingIntent, remoteInput) = pair
            val intent = Intent()
            val bundle = android.os.Bundle()
            bundle.putCharSequence(remoteInput.resultKey, text)
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
            return try {
                pendingIntent.send(context, 0, intent)
                true
            } catch (e: PendingIntent.CanceledException) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotifStore.init(applicationContext)
        appContext = applicationContext
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Bij (her)verbinden: haal actieve meldingen op zodat de lijst direct gevuld is.
        activeNotifications?.forEach { handleNotification(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handleNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotifStore.removeByKey(sbn.key)
        replyActions.remove(sbn.key)
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        // Sla groep-samenvattingen en lege meldingen over.
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        var replyPendingIntent: PendingIntent? = null
        var replyRemoteInput: RemoteInput? = null
        sbn.notification.actions?.forEach { action ->
            action.remoteInputs?.forEach { ri ->
                replyPendingIntent = action.actionIntent
                replyRemoteInput = ri
            }
        }
        val hasReply = replyPendingIntent != null && replyRemoteInput != null
        if (hasReply) {
            replyActions[sbn.key] = Pair(replyPendingIntent!!, replyRemoteInput!!)
        }

        NotifStore.addOrUpdate(
            NotifItem(
                key = sbn.key,
                packageName = sbn.packageName,
                appLabel = appLabel,
                title = title,
                text = text,
                postTime = sbn.postTime,
                hasReplyAction = hasReply
            )
        )
    }
}
