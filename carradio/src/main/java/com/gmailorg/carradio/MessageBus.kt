package com.gmailorg.carradio

import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** In-memory status/logbus die ook activity-recreatie op deze headunit overleeft. */
object MessageBus {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(String) -> Unit>()
    private val statusListeners = mutableListOf<(String) -> Unit>()
    private val history = ArrayDeque<String>()

    @Volatile private var currentStatus: String = "Wachten op verbinding met je telefoon..."

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        history.toList().asReversed().take(12).asReversed().forEach { listener(it) }
    }

    fun removeListener(listener: (String) -> Unit) { listeners.remove(listener) }

    fun addStatusListener(listener: (String) -> Unit) {
        statusListeners.add(listener)
        listener(currentStatus)
    }

    fun removeStatusListener(listener: (String) -> Unit) { statusListeners.remove(listener) }

    fun postMessage(text: String) {
        synchronized(history) {
            while (history.size >= 40) history.removeFirst()
            history.addLast(text)
        }
        mainHandler.post { listeners.toList().forEach { it(text) } }
    }

    fun postStatus(text: String) {
        currentStatus = text
        mainHandler.post { statusListeners.toList().forEach { it(text) } }
    }
}
