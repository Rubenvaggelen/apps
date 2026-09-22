package com.gmailorg.hub

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.IBinder
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
        private val prefetchedMediaTokens = ConcurrentHashMap.newKeySet<String>()
        private val prefetchInFlightTokens = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        var lastWhatsAppReplyKey: String? = null

        private var appContext: android.content.Context? = null
        @Volatile private var instance: UnifiedNotificationListener? = null

        fun ensureCarRadioBound(context: Context): Boolean {
            val listener = instance
            if (listener != null) return listener.ensureCarRadioServerRunning()
            return false
        }

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

    private var carRadioBound = false
    private val carRadioBinding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            carRadioBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carRadioBound = false
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
        if (carRadioBound) {
            try { unbindService(carRadioBinding) } catch (_: Exception) {}
            carRadioBound = false
        }
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

    private fun ensureCarRadioServerRunning(): Boolean {
        if (!CarRadioForwarder.isEnabled(applicationContext)) return false
        if (carRadioBound) return true
        return try {
            carRadioBound = bindService(
                Intent(this, CarRadioConnectionService::class.java),
                carRadioBinding,
                Context.BIND_AUTO_CREATE
            )
            carRadioBound
        } catch (_: Exception) {
            false
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

    private fun cacheWhatsAppMedia(keys: Set<String>, uri: Uri, mime: String, image: Boolean, contact: String) {
        if (keys.isEmpty()) return
        val direct = CarMediaSource(uri, mime)
        keys.forEach { key ->
            if (image) imageSources[key] = direct else voiceNoteSources[key] = direct
        }

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
                val file = File(dir, "${if (image) "image" else "audio"}_${System.currentTimeMillis()}_${keys.first().hashCode()}$ext")
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
                    keys.forEach { key ->
                        if (image) imageSources[key] = cached else voiceNoteSources[key] = cached
                    }
                    prefetchMediaToCar(contact, cached)
                }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { runCatching { it.delete() } }
            } catch (_: Exception) {
                // Directe URI blijft als fallback staan zolang WhatsApp hem toestaat.
            }
        }.start()
    }

    private fun prefetchMediaToCar(contact: String, source: CarMediaSource) {
        if (contact.isBlank()) return
        val sourceId = source.uri?.toString()?.takeIf { it.isNotBlank() }
            ?: source.cachedPath.orEmpty()
        val token = contact.lowercase(Locale.ROOT) + "|" + sourceId
        if (sourceId.isBlank() || prefetchedMediaTokens.contains(token) || !prefetchInFlightTokens.add(token)) return

        Thread {
            try {
                // Verse WhatsApp-media kan net vóór de car-socket of vóór WA_MSG binnenkomen.
                // Blijf daarom kort opnieuw proberen; één race mag de media niet definitief missen.
                repeat(15) { attempt ->
                    if (CarRadioConnectionService.isRadioConnected()) {
                        val bytes = readMediaSourceForPrefetch(source, 20_000_000)
                        if (bytes != null && bytes.isNotEmpty()) {
                            if (attempt == 0) Thread.sleep(650L)
                            val sent = CarRadioConnectionService.sendMediaBytes(
                                contact,
                                source.mime ?: "application/octet-stream",
                                bytes,
                                autoPresent = false
                            )
                            if (sent) {
                                prefetchedMediaTokens.add(token)
                                return@Thread
                            }
                        }
                    }
                    Thread.sleep(2_000L)
                }
            } catch (_: Exception) {
            } finally {
                prefetchInFlightTokens.remove(token)
            }
        }.start()
    }

    private fun readMediaSourceForPrefetch(source: CarMediaSource, maxBytes: Int): ByteArray? {
        try {
            source.cachedPath?.takeIf { it.isNotBlank() }?.let { path ->
                val file = File(path)
                if (file.exists() && file.length() in 1..maxBytes.toLong()) return file.readBytes()
            }
            val uri = source.uri ?: return null
            return contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (out.size() <= maxBytes) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    if (out.size() + read > maxBytes) return@use null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun notificationMediaContact(extras: android.os.Bundle, fallbackTitle: String): String {
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim().orEmpty()
        if (conversationTitle.isNotBlank()) return conversationTitle

        val title = fallbackTitle.trim()
        val genericTitle = title.equals("WhatsApp", true) ||
            Regex("""^\d+\s+(new|nieuwe)?\s*(messages|berichten)?""", RegexOption.IGNORE_CASE).containsMatchIn(title)
        if (!genericTitle && title.isNotBlank()) return title

        return try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { bundles ->
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
                    .asReversed()
                    .firstOrNull { it.dataUri != null }
                    ?.senderPerson?.name?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
            } ?: title
        } catch (_: Exception) {
            title
        }
    }

    private fun notificationConversationKeys(extras: android.os.Bundle, fallbackTitle: String): LinkedHashSet<String> {
        val keys = LinkedHashSet<String>()
        fun addName(value: CharSequence?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) keys.add(conversationKey(text))
        }
        addName(extras.getCharSequence(Notification.EXTRA_TITLE))
        addName(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        addName(fallbackTitle)
        try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { bundles ->
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).forEach { msg ->
                    addName(msg.senderPerson?.name)
                }
            }
        } catch (_: Exception) {}
        return keys
    }

    private fun collectMediaUris(bundle: android.os.Bundle, out: MutableList<Pair<String, Uri>>, depth: Int = 0) {
        if (depth > 4) return
        bundle.keySet().forEach { key ->
            val lowerKey = key.lowercase(Locale.ROOT)
            if (lowerKey.contains("avatar") || lowerKey.contains("person") || lowerKey.contains("largeicon")) return@forEach
            val value = runCatching { bundle.get(key) }.getOrNull()
            when (value) {
                is Uri -> out.add(key to value)
                is CharSequence -> {
                    val text = value.toString()
                    if (text.startsWith("content://") || text.startsWith("file://")) {
                        runCatching { Uri.parse(text) }.getOrNull()?.let { out.add(key to it) }
                    }
                }
                is android.os.Bundle -> collectMediaUris(value, out, depth + 1)
                is Array<*> -> value.forEach { item ->
                    when (item) {
                        is android.os.Bundle -> collectMediaUris(item, out, depth + 1)
                        is Uri -> out.add(key to item)
                    }
                }
                is Iterable<*> -> value.forEach { item ->
                    when (item) {
                        is android.os.Bundle -> collectMediaUris(item, out, depth + 1)
                        is Uri -> out.add(key to item)
                    }
                }
            }
        }
    }

    private fun cachePictureExtra(extras: android.os.Bundle, keys: Set<String>, contact: String): Boolean {
        val value = runCatching { extras.get(Notification.EXTRA_PICTURE) }.getOrNull() ?: return false
        val bitmap = when (value) {
            is Bitmap -> value
            is Icon -> {
                val drawable = runCatching { value.loadDrawable(this) }.getOrNull() ?: return false
                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
                    val canvas = Canvas(target)
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(canvas)
                }
            }
            else -> return false
        }
        return try {
            val dir = File(cacheDir, "wa_notification_media").apply { mkdirs() }
            val file = File(dir, "image_${System.currentTimeMillis()}_picture.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val source = CarMediaSource(null, "image/jpeg", file.absolutePath)
            keys.forEach { imageSources[it] = source }
            prefetchMediaToCar(contact, source)
            true
        } catch (_: Exception) {
            false
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

        val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

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
            val mediaContact = notificationMediaContact(extras, title)
            val key = conversationKey(mediaContact)
            val keys = notificationConversationKeys(extras, mediaContact).apply { if (key.isNotBlank()) add(key) }
            val lower = text.lowercase(Locale.ROOT)
            val looksLikeVoice = lower.contains("spraakbericht") || lower.contains("voice message") ||
                lower.contains("voice note") || lower.contains("audio message") || lower.contains("audiobericht") ||
                lower.startsWith("🎤")
            val looksLikeImage = lower.contains("foto") || lower.contains("photo") ||
                lower.contains("afbeelding") || lower.contains("image") || lower.startsWith("📷") || lower.startsWith("🖼")

            if (looksLikeVoice) {
                mediaMimeHint = "audio/*"
                keys.forEach { mediaKey ->
                    sbn.notification.contentIntent?.let { voiceNoteContentIntents[mediaKey] = it }
                }
                sbn.notification.actions?.firstOrNull { action ->
                    val label = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    label.contains("afspelen") || label == "play" || label.contains("listen")
                }?.actionIntent?.let { action ->
                    keys.forEach { voiceNotePlayActions[it] = action }
                }
            } else if (looksLikeImage) {
                mediaMimeHint = "image/*"
            }

            // 1) Android MessagingStyle: de meest betrouwbare bron als WhatsApp hem vrijgeeft.
            try {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { bundles ->
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
                        .asReversed()
                        .forEach { message ->
                            val uri = message.dataUri ?: return@forEach
                            val mime = message.dataMimeType.orEmpty().ifBlank {
                                runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
                            }.ifBlank {
                                when {
                                    looksLikeVoice -> "audio/*"
                                    looksLikeImage -> "image/*"
                                    else -> ""
                                }
                            }
                            when {
                                mime.startsWith("audio/") -> {
                                    mediaMimeHint = mime
                                    cacheWhatsAppMedia(keys, uri, mime, image = false, contact = mediaContact)
                                }
                                mime.startsWith("image/") -> {
                                    mediaMimeHint = mime
                                    cacheWhatsAppMedia(keys, uri, mime, image = true, contact = mediaContact)
                                }
                            }
                        }
                }
            } catch (_: Exception) {}

            // 2) WhatsApp/Android-versies stoppen de URI soms in een andere notification-extra.
            // Scan die extras direct en kopieer de media meteen naar onze eigen cache.
            val uriCandidates = mutableListOf<Pair<String, Uri>>()
            collectMediaUris(extras, uriCandidates)
            uriCandidates.distinctBy { it.second.toString() }.forEach { (sourceKey, uri) ->
                val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty().ifBlank {
                    when {
                        looksLikeVoice -> "audio/*"
                        looksLikeImage -> "image/*"
                        else -> ""
                    }
                }
                when {
                    mime.startsWith("audio/") -> {
                        mediaMimeHint = mime
                        cacheWhatsAppMedia(keys, uri, mime, image = false, contact = mediaContact)
                    }
                    mime.startsWith("image/") && looksLikeImage &&
                        !sourceKey.lowercase(Locale.ROOT).contains("icon") -> {
                        mediaMimeHint = mime
                        cacheWhatsAppMedia(keys, uri, mime, image = true, contact = mediaContact)
                    }
                }
            }

            // 3) BigPicture-notificaties bevatten soms alleen een Bitmap/Icon en geen URI.
            if (looksLikeImage && keys.none { imageSources.containsKey(it) }) {
                if (cachePictureExtra(extras, keys, mediaContact)) mediaMimeHint = "image/jpeg"
            }
        }

        // Op sommige WhatsApp/Android-versies zit verse media alleen in de group-summary.
        // Die media is hierboven nu wel verwerkt, maar de summary zelf mag geen extra chatregel worden.
        if (isGroupSummary) return

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
