package com.gmailorg.carradio

import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** In-memory status/logbus plus een data-change signaal voor gesprekken/contacten. */
object MessageBus {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(String) -> Unit>()
    private val statusListeners = mutableListOf<(String) -> Unit>()
    private val dataListeners = mutableListOf<() -> Unit>()
    private val history = ArrayDeque<String>()

    @Volatile private var currentStatus: String = "Wachten op verbinding met je telefoon..."

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        history.toList().asReversed().take(20).asReversed().forEach { listener(it) }
    }
    fun removeListener(listener: (String) -> Unit) { listeners.remove(listener) }

    fun addStatusListener(listener: (String) -> Unit) {
        statusListeners.add(listener)
        listener(currentStatus)
    }
    fun removeStatusListener(listener: (String) -> Unit) { statusListeners.remove(listener) }

    fun addDataListener(listener: () -> Unit) {
        dataListeners.add(listener)
        listener()
    }
    fun removeDataListener(listener: () -> Unit) { dataListeners.remove(listener) }

    fun postMessage(text: String) {
        synchronized(history) {
            while (history.size >= 80) history.removeFirst()
            history.addLast(text)
        }
        mainHandler.post { listeners.toList().forEach { it(text) } }
    }

    fun history(): List<String> = synchronized(history) { history.toList() }

    fun postStatus(text: String) {
        currentStatus = text
        mainHandler.post { statusListeners.toList().forEach { it(text) } }
    }

    fun currentStatus(): String = currentStatus

    fun postDataChanged() {
        mainHandler.post { dataListeners.toList().forEach { it() } }
    }
}
