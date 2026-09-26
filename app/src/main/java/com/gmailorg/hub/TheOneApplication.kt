package com.gmailorg.hub

import android.app.Application
import android.content.ComponentCallbacks2

class TheOneApplication : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            RemoteUsbMusicClient.clearToken(this)
        }
    }
}
