package com.gmailorg.hub

import android.app.Application
import android.content.ComponentCallbacks2

class TheOneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AccessRequestNotificationWorker.schedule(this)
        AccessRequestNotificationWorker.checkNow(this)
        DailyMotivationWorker.schedule(this)
        SupermarketGeofenceManager.reArmAfterBootIfEnabled(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            RemoteUsbMusicClient.clearToken(this)
        }
    }
}
