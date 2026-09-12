package com.gmailorg.hub

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Vangt meldingen op van alle apps voor The One op de telefoon.
 * Voor de autoradio wordt alleen WhatsApp doorgestuurd, met een optionele allow-list.
 */
class UnifiedNotificationListener : NotificationListenerService() {

    companion object {
        private val replyActions = ConcurrentHashMap<String, Pair<PendingIntent, RemoteInput>>()
        private val whatsAppReplyKeyByConversation = ConcurrentHashMap<String, String>()
        private val voiceNoteSources = ConcurrentHashMap<String, VoiceNoteSource>()
        private val voiceNotePlayActions = ConcurrentHashMap<String, PendingIntent>()
        private val voiceNoteContentIntents = ConcurrentHashMap<String, PendingIntent>()

        data class VoiceNoteSource(val uri: Uri?, val mime: String?)

        @Volatile
        var lastWhatsAppReplyKey: String? = null

        private var appContext: android.content.Context? = null

        private fun conversationKey(title: String): String = title.trim().lowercase(Locale.ROOT)

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
            } catch (_: PendingIntent.CanceledException) {
                replyActions.remove(key)
                whatsAppReplyKeyByConversation.entries.removeAll { it.value == key }
                false
            }
        }

        fun sendReplyToConversation(title: String, text: String): Boolean {
            val key = whatsAppReplyKeyByConversation[conversationKey(title)] ?: return false
            return sendReply(key, text)
        }

        fun hasReplyTarget(title: String): Boolean =
            whatsAppReplyKeyByConversation.containsKey(conversationKey(title))

        fun voiceNoteSourceForConversation(title: String): VoiceNoteSource? =
            voiceNoteSources[conversationKey(title)]

        fun triggerVoiceNoteAction(title: String): Boolean {
            if (appContext == null) return false
            val key = conversationKey(title)
            val pending = voiceNotePlayActions[key] ?: voiceNoteContentIntents[key] ?: return false
            return try { pending.send(); true } catch (_: Exception) { false }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotifStore.init(applicationContext)
        appContext = applicationContext
        purgeCarRadioStatusFromNotificationHistory()
        ensureCarRadioServerRunning()
    }

    private fun purgeCarRadioStatusFromNotificationHistory() {
        NotifStore.removeWhere { item ->
            item.packageName == packageName && item.title == "The One – Autoradio"
        }
    }

    private fun isCarRadioStatusNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != packageName) return false
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val channel = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            sbn.notification.channelId
        } else null
        return channel == "car_radio_connection" || title == "The One – Autoradio"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        purgeCarRadioStatusFromNotificationHistory()
        ensureCarRadioServerRunning()
        activeNotifications?.forEach { handleNotification(it) }
    }

    private fun ensureCarRadioServerRunning() {
        if (!CarRadioForwarder.isEnabled(applicationContext)) return
        if (!CarRadioForwarder.isNearby(applicationContext) && !CarRadioConnectionService.isRadioConnected()) return
        try {
            CarRadioConnectionService.start(applicationContext)
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handleNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotifStore.removeByKey(sbn.key)
        // Voor WhatsApp bewaren we de laatste RemoteInput zolang die PendingIntent nog geldig is.
        // Daardoor kan The One Car binnen een gesprek vaak nog een vervolgreply sturen, ook
        // als Android de zichtbare melding al heeft weggehaald. Bij een CanceledException
        // wordt de cache hierboven automatisch opgeruimd.
        if (sbn.packageName != "com.whatsapp") {
            replyActions.remove(sbn.key)
        }
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        // The foreground service notification is operational state, not a user message.
        // Never show it inside The One's own Meldingen screen.
        if (isCarRadioStatusNotification(sbn)) {
            NotifStore.removeByKey(sbn.key)
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
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
            if (sbn.packageName == "com.whatsapp") {
                lastWhatsAppReplyKey = sbn.key
                whatsAppReplyKeyByConversation[conversationKey(title)] = sbn.key
            }
        }

        if (sbn.packageName == "com.whatsapp") {
            val key = conversationKey(title)
            val lower = text.lowercase(Locale.ROOT)
            val looksLikeVoice = lower.contains("spraakbericht") || lower.contains("voice message") || lower.contains("audio message")
            if (looksLikeVoice) {
                sbn.notification.contentIntent?.let { voiceNoteContentIntents[key] = it }
                sbn.notification.actions?.firstOrNull { action ->
                    val label = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    label.contains("afspelen") || label == "play" || label.contains("listen")
                }?.actionIntent?.let { voiceNotePlayActions[key] = it }
            }

            try {
                val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (bundles != null) {
                    val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
                    val withAudio = messages.lastOrNull {
                        it.dataMimeType?.startsWith("audio/") == true && it.dataUri != null
                    }
                    if (withAudio != null) {
                        voiceNoteSources[key] = VoiceNoteSource(withAudio.dataUri, withAudio.dataMimeType)
                        sbn.notification.contentIntent?.let { voiceNoteContentIntents[key] = it }
                    }
                }
            } catch (_: Exception) {}
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

        if (sbn.packageName == "com.whatsapp") {
            WhatsAppCarFilterStore.registerSeen(applicationContext, title)
        }
        CarRadioForwarder.forwardIfEnabled(
            applicationContext,
            sbn.packageName,
            title,
            text,
            sbn.postTime
        )
    }
}
