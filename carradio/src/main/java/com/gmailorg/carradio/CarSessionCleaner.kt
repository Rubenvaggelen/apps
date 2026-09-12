package com.gmailorg.carradio

import android.content.Context
import java.io.File

/** Wist alleen ritgebonden/tijdelijke data; voorkeuren en contactinstellingen blijven staan. */
object CarSessionCleaner {
    fun clearChats(context: Context) {
        ConversationStore.clear(context)
        clearVoiceCache(context)
        MessageBus.postDataChanged()
    }

    fun clearNotifications(context: Context) {
        CarNotificationStore.clear(context)
        MessageBus.postDataChanged()
    }

    fun clearAllEphemeral(context: Context) {
        ConversationStore.clear(context)
        CarNotificationStore.clear(context)
        clearVoiceCache(context)
        MessageBus.clearHistory()
        MessageBus.postDataChanged()
    }

    private fun clearVoiceCache(context: Context) {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("wa_voice_")) {
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }
}
