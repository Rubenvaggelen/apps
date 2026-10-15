package com.gmailorg.hub

/**
 * Visibility is a device-local presentation concern; never confuse app
 * installation with server-granted permissions.
 *
 * Every TileType.APP requires an installed, launchable package, including
 * Family links (DJ, Run, Cinema, Series, TV) and custom shortcuts.
 * Built-in The One features are independent of external APK installation.
 */
internal object UserTileVisibility {
    fun isVisible(tile: HomeTile, isInstalled: (String) -> Boolean): Boolean =
        if (tile.type != TileType.APP) true
        else tile.packageName?.takeIf { it.isNotBlank() }?.let(isInstalled) == true
}
