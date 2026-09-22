package com.gmailorg.hub

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class CarMediaSource(
    val uri: Uri?,
    val mime: String?,
    val cachedPath: String? = null
)

/**
 * Vangt meldingen op van alle apps voor The One op de telefoon.
 * Voor de autoradio wordt alleen WhatsApp doorgestuurd, met een optionele allow-list.
 */
class UnifiedNotificationListener : NotificationListenerService() {

    companion object {
        private val replyActions = ConcurrentHashMap<String, Pair<PendingIntent, RemoteInput>>()
        private val whatsAppReplyKeyByConversation = ConcurrentHashMap<String, String>()
        private val voiceNoteSources = ConcurrentHashMap<String, CarMediaSource>()
        private val imageSources = ConcurrentHashMap<String, CarMediaSource>()
        private val voiceNotePlayActions = ConcurrentHashMap<String, PendingIntent>()
        private val voiceNoteContentIntents = ConcurrentHashMap<String, PendingIntent>()

        @Volatile
        var lastWhatsAppReplyKey: String? = null

        private var appContext: android.content.Context? = null
        @Volatile private var instance: UnifiedNotificationListener? = null

        fun rescanFinanceNotifications(): Int {
            val service = instance ?: return -1
            return try {
                val notifications = service.activeNotifications.orEmpty()
                notifications.forEach { service.handleFinanceNotification(it) }
                notifications.size
            } catch (_: Exception) {
                -1
            }
        }

        private fun conversationKey(title: String): String =
            title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        private fun isWhatsAppPackage(packageName: String): Boolean =
            packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b"

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
            } catch (_: Exception) {
                replyActions.remove(key)
                whatsAppReplyKeyByConversation.entries.removeAll { it.value == key }
                false
            }
        }

        fun sendReplyToConversation(title: String, text: String): Boolean {
            val wanted = conversationKey(title)

            fun tryCached(): Boolean {
                val key = whatsAppReplyKeyByConversation[wanted] ?: return false
                return sendReply(key, text)
            }

            if (tryCached()) return true

            // WhatsApp vernieuwt of vervangt RemoteInput/PendingIntent regelmatig.
            // Herlees daarom de actieve meldingen vlak voor een reply in plaats van
            // uitsluitend te vertrouwen op een mogelijk verouderde cache.
            instance?.refreshWhatsAppReplyTargets()
            return tryCached()
        }

        fun hasReplyTarget(title: String): Boolean {
            val wanted = conversationKey(title)
            if (whatsAppReplyKeyByConversation.containsKey(wanted)) return true
            instance?.refreshWhatsAppReplyTargets()
            return whatsAppReplyKeyByConversation.containsKey(wanted)
        }

        fun voiceNoteSourceForConversation(title: String): CarMediaSource? =
            voiceNoteSources[conversationKey(title)]

        fun imageSourceForConversation(title: String): CarMediaSource? =
            imageSources[conversationKey(title)]

        fun mediaSourceForConversation(title: String): CarMediaSource? {
            val key = conversationKey(title)
            return imageSources[key] ?: voiceNoteSources[key]
        }

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
        instance = this
        purgeCarRadioStatusFromNotificationHistory()
        ensureCarRadioServerRunning()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
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
        val hotspotActive = CarHotspotDetector.isHotspotLikelyActive(applicationContext)
        if (!CarRadioForwarder.isNearby(applicationContext) && !hotspotActive && !CarRadioConnectionService.isRadioConnected()) return
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
        if (!isWhatsAppPackage(sbn.packageName)) {
            replyActions.remove(sbn.key)
        }
    }

    private fun refreshWhatsAppReplyTargets() {
        try {
            activeNotifications.orEmpty()
                .filter { isWhatsAppPackage(it.packageName) }
                .forEach { cacheWhatsAppReplyAction(it) }
        } catch (_: Exception) {
        }
    }

    private fun cacheWhatsAppReplyAction(sbn: StatusBarNotification) {
        if (!isWhatsAppPackage(sbn.packageName)) return

        var replyPendingIntent: PendingIntent? = null
        var replyRemoteInput: RemoteInput? = null

        sbn.notification.actions?.forEach { action ->
            val inputs = action.remoteInputs.orEmpty()
            val preferred = inputs.firstOrNull { it.allowFreeFormInput } ?: inputs.firstOrNull()
            if (preferred != null) {
                // Een actie die expliciet als Reply gemarkeerd is krijgt voorrang.
                if (replyRemoteInput == null ||
                    action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY) {
                    replyPendingIntent = action.actionIntent
                    replyRemoteInput = preferred
                }
            }
        }

        val pending = replyPendingIntent ?: return
        val remote = replyRemoteInput ?: return
        replyActions[sbn.key] = Pair(pending, remote)
        lastWhatsAppReplyKey = sbn.key

        val names = LinkedHashSet<String>()
        val extras = sbn.notification.extras
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }?.let { names.add(it) }

        try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { bundles ->
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).forEach { msg ->
                    msg.senderPerson?.name?.toString()?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
            }
        } catch (_: Exception) {
        }

        names.forEach { name ->
            whatsAppReplyKeyByConversation[conversationKey(name)] = sbn.key
        }
    }

    private fun handleFinanceNotification(sbn: StatusBarNotification) {
        // Een group summary kan bedragen uit meerdere child-notificaties samenvoegen.
        // Alleen de echte child-melding verwerken voorkomt dubbele aftrek.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        if (FinanceNotificationProcessor.sourceNameFor(sbn.packageName, appLabel) == null) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        val pieces = LinkedHashSet<String>()
        fun addText(value: Any?) {
            when (value) {
                is CharSequence -> value.toString().takeIf { it.isNotBlank() }?.let { pieces.add(it) }
                is Array<*> -> value.forEach { addText(it) }
                is Iterable<*> -> value.forEach { addText(it) }
                is android.os.Bundle -> value.keySet().forEach { key -> addText(value.get(key)) }
            }
        }

        // Bekende Android notificationvelden.
        listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_CONVERSATION_TITLE,
            Notification.EXTRA_TEXT_LINES
        ).forEach { key -> addText(extras.get(key)) }

        // Sommige bankapps gebruiken eigen extras. Neem alleen tekstuele waarden mee.
        extras.keySet().forEach { key ->
            runCatching { addText(extras.get(key)) }
        }

        // MessagingStyle kan tekst bevatten die niet in EXTRA_TEXT staat.
        try {
            val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (bundles != null) {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).forEach { msg ->
                    addText(msg.text)
                    addText(msg.senderPerson?.name)
                }
            }
        } catch (_: Exception) {
        }

        // Actietitels zoals "Betalen" / "Betaald" kunnen helpen bij classificatie.
        sbn.notification.actions?.forEach { action -> addText(action.title) }

        try {
            FinanceNotificationProcessor.process(
                context = applicationContext,
                packageName = sbn.packageName,
                appLabel = appLabel,
                title = title,
                body = pieces.joinToString("\n"),
                notificationKey = sbn.key,
                postTime = sbn.postTime
            )
        } catch (_: Exception) {
            // Finance mag de algemene listener nooit laten crashen.
        }
    }

    private fun cacheWhatsAppMedia(key: String, uri: Uri, mime: String, image: Boolean) {
        Thread {
            try {
                val dir = File(cacheDir, "wa_notification_media").apply { mkdirs() }
                val ext = when {
                    mime.contains("jpeg", true) || mime.contains("jpg", true) -> ".jpg"
                    mime.contains("png", true) -> ".png"
                    mime.contains("webp", true) -> ".webp"
                    mime.contains("gif", true) -> ".gif"
                    mime.contains("ogg", true) || mime.contains("opus", true) -> ".ogg"
                    mime.contains("mpeg", true) || mime.contains("mp3", true) -> ".mp3"
                    mime.contains("mp4", true) || mime.contains("m4a", true) -> ".m4a"
                    mime.contains("aac", true) -> ".aac"
                    mime.contains("3gpp", true) || mime.contains("3gp", true) -> ".3gp"
                    mime.contains("wav", true) -> ".wav"
                    else -> if (image) ".img" else ".audio"
                }
                val file = File(dir, "${if (image) "image" else "audio"}_${System.currentTimeMillis()}_${key.hashCode()}$ext")
                val input = contentResolver.openInputStream(uri) ?: return@Thread
                var total = 0
                input.use { source ->
                    file.outputStream().use { target ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > 20_000_000) {
                                runCatching { file.delete() }
                                return@Thread
                            }
                            target.write(buffer, 0, read)
                        }
                    }
                }
                if (file.exists() && file.length() > 0L) {
                    val cached = CarMediaSource(uri, mime, file.absolutePath)
                    if (image) imageSources[key] = cached else voiceNoteSources[key] = cached
                }
                // Houd cache begrensd: oude media uit eerdere ritten mag weg.
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { runCatching { it.delete() } }
            } catch (_: Exception) {
                // URI blijft als fallback beschikbaar zolang WhatsApp die toestaat.
            }
        }.start()
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        // The foreground service notification is operational state, not a user message.
        // Never show it inside The One's own Meldingen screen.
        if (isCarRadioStatusNotification(sbn)) {
            NotifStore.removeByKey(sbn.key)
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Verwerk financiën vóór de algemene group-summary filter. De finance helper
        // filtert zelf bekende bronnen en verzamelt veel meer tekstvelden dan EXTRA_TEXT.
        handleFinanceNotification(sbn)

        // Nieuwere WhatsApp-versies kunnen de bruikbare RemoteInput juist op een
        // summary/conversation-notificatie zetten. Sla de replyactie daarom op vóór
        // de group-summary return; ontvangen berichten bleven anders wel werken,
        // maar terugsturen vanuit The One Car niet.
        if (isWhatsAppPackage(sbn.packageName)) {
            cacheWhatsAppReplyAction(sbn)
        }

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
            if (isWhatsAppPackage(sbn.packageName)) {
                lastWhatsAppReplyKey = sbn.key
                whatsAppReplyKeyByConversation[conversationKey(title)] = sbn.key
            }
        }

        var mediaMimeHint: String? = null
        if (isWhatsAppPackage(sbn.packageName)) {
            val key = conversationKey(title)
            val lower = text.lowercase(Locale.ROOT)
            val looksLikeVoice = lower.contains("spraakbericht") || lower.contains("voice message") || lower.contains("audio message")
            val looksLikeImage = lower.contains("foto") || lower.contains("photo") ||
                lower.contains("afbeelding") || lower.contains("image") || lower.startsWith("📷") || lower.startsWith("🖼")

            if (looksLikeVoice) {
                mediaMimeHint = "audio/*"
                sbn.notification.contentIntent?.let { voiceNoteContentIntents[key] = it }
                sbn.notification.actions?.firstOrNull { action ->
                    val label = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    label.contains("afspelen") || label == "play" || label.contains("listen")
                }?.actionIntent?.let { voiceNotePlayActions[key] = it }
            } else if (looksLikeImage) {
                mediaMimeHint = "image/*"
            }

            try {
                val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (bundles != null) {
                    val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
                    val withMedia = messages.lastOrNull {
                        val mime = it.dataMimeType.orEmpty()
                        it.dataUri != null && (mime.startsWith("audio/") || mime.startsWith("image/"))
                    }
                    if (withMedia != null) {
                        val mime = withMedia.dataMimeType.orEmpty()
                        val uri = withMedia.dataUri
                        mediaMimeHint = mime
                        if (uri != null) {
                            if (mime.startsWith("audio/")) {
                                voiceNoteSources[key] = CarMediaSource(uri, mime)
                                cacheWhatsAppMedia(key, uri, mime, image = false)
                                sbn.notification.contentIntent?.let { voiceNoteContentIntents[key] = it }
                            } else if (mime.startsWith("image/")) {
                                imageSources[key] = CarMediaSource(uri, mime)
                                cacheWhatsAppMedia(key, uri, mime, image = true)
                            }
                        }
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

        if (isWhatsAppPackage(sbn.packageName)) {
            WhatsAppCarFilterStore.registerSeen(applicationContext, title)
        }
        CarRadioForwarder.forwardIfEnabled(
            applicationContext,
            sbn.packageName,
            title,
            text,
            sbn.postTime,
            mediaMimeHint
        )
    }
}
