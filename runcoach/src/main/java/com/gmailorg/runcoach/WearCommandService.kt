package com.gmailorg.runcoach

import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Ontvangt knoppen van het horloge: pauze, hervatten, stop, nu starten, sync. */
class WearCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearBridge.PATH_CMD) return
        val cmd = String(event.data, Charsets.UTF_8)
        Handler(Looper.getMainLooper()).post {
            val status = RunRepository.snapshot.status
            try {
                when (cmd) {
                    "pause" -> if (status == RunStatus.RUNNING) RunService.action(this, RunService.ACTION_PAUSE)
                    "resume" -> if (status == RunStatus.PAUSED) RunService.action(this, RunService.ACTION_RESUME)
                    "startnow" -> if (status == RunStatus.WAITING_GPS) RunService.action(this, RunService.ACTION_START_NOW)
                    "stop" -> if (status == RunStatus.RUNNING || status == RunStatus.PAUSED || status == RunStatus.WAITING_GPS)
                        RunService.action(this, RunService.ACTION_STOP)
                }
            } catch (e: Exception) {
                // service niet bereikbaar (bijv. geen actieve run)
            }
            WearBridge.publish(this, RunRepository.snapshot, force = true)
        }
    }
}
