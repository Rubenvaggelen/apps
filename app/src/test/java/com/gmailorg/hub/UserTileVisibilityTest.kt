package com.gmailorg.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTileVisibilityTest {
    private val externalPackages = listOf(
        "com.gmailorg.thedj",
        "com.gmailorg.runcoach",
        "com.theone.cinema",
        "com.theone.series",
        "com.theone.tv.phone",
        "com.whatsapp",
        "com.google.android.apps.chromecast.app"
    )

    @Test
    fun missingStandaloneAppsAreNeverVisibleEvenWithFamilyScope() {
        for (packageName in externalPackages) {
            val tile = HomeTile(packageName, TileType.APP, "Installed-only", packageName)
            assertFalse("$packageName incorrectly shown when not installed",
                UserTileVisibility.isVisible(tile) { false })
        }
    }

    @Test
    fun installedStandaloneAppsBecomeVisibleWithoutAnyMainUpdate() {
        for (packageName in externalPackages) {
            val tile = HomeTile(packageName, TileType.APP, "Installed-only", packageName)
            assertTrue("$packageName should show when installed",
                UserTileVisibility.isVisible(tile) { it == packageName })
        }
    }

    @Test
    fun customShortcutDisappearsWhenUninstalled() {
        val tile = HomeTile("com.example.external", TileType.APP, "Custom", "com.example.external")
        assertTrue(UserTileVisibility.isVisible(tile) { it == "com.example.external" })
        assertFalse(UserTileVisibility.isVisible(tile) { false })
        assertFalse(UserTileVisibility.isVisible(tile.copy(packageName = null)) { true })
    }

    @Test
    fun builtInTheOneTilesRemainVisibleWithoutExternalApks() {
        for (type in listOf(
            TileType.NOTIFICATIONS, TileType.MAIL, TileType.ROUTE,
            TileType.HOUSEHOLD, TileType.MOVIES, TileType.PARKING,
            TileType.ASK, TileType.RECIPES, TileType.NEWS,
            TileType.RADIO, TileType.CURRENCY, TileType.ADD_BUTTON
        )) {
            val tile = HomeTile(type.name.lowercase(), type, type.name)
            assertTrue("$type is a built-in app feature",
                UserTileVisibility.isVisible(tile) { false })
        }
    }
}
