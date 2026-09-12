package com.gmailorg.carradio

import android.os.Handler
import android.os.Looper

/** Simpele in-memory bus: de Bluetooth-service post hier berichten, het scherm luistert mee. */
object MessageBus {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(String) -> Unit>()
    private val statusListeners = mutableListOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun addStatusListener(listener: (String) -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: (String) -> Unit) {
        statusListeners.remove(listener)
    }

    fun postMessage(text: String) {
        mainHandler.post { listeners.forEach { it(text) } }
    }

    fun postStatus(text: String) {
        mainHandler.post { statusListeners.forEach { it(text) } }
    }
}
