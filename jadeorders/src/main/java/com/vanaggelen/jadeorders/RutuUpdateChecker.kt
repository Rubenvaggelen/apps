package com.vanaggelen.jadeorders

import android.app.Activity

/**
 * Google Play distribution: updates are installed by Google Play.
 * The existing RUTU APK-based updater and REQUEST_INSTALL_PACKAGES permission
 * remain untouched in the separate sideload/production branch.
 */
object RutuUpdateChecker {
    @Suppress("UNUSED_PARAMETER")
    fun checkForUpdate(activity: Activity) {
        // No external APK download, browser navigation, extra tab, or installer.
        // Google Play manages updates in the background.
    }
}
