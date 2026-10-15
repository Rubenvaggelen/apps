package com.gmailorg.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensedTileVisibilityTest {
    private val familyTiles = listOf(
        "com.theone.cinema", "com.theone.series", "com.theone.tv.phone"
    )

    @Test fun ownerSeesFamilyTilesByDefault() {
        familyTiles.forEach { id ->
            assertTrue("Owner's unchanged Main must keep default $id tile",
                LicensedTileVisibility.resolveVisibility(id, null, true))
        }
    }

    @Test fun explicitHideWorksOnOwnerMain() {
        familyTiles.forEach { id ->
            assertFalse("$id must remain hidden despite owner role",
                LicensedTileVisibility.resolveVisibility(id, false, true))
        }
    }

    @Test fun restoreMakesFamilyTileVisibleAgain() {
        familyTiles.forEach { id ->
            assertTrue("Restored $id should show",
                LicensedTileVisibility.resolveVisibility(id, true, true))
        }
    }

    @Test fun nonOwnerDefaultAndExplicitVisibilityAreUnaffected() {
        familyTiles.forEach { id ->
            assertFalse(LicensedTileVisibility.resolveVisibility(id, null, false))
            assertTrue(LicensedTileVisibility.resolveVisibility(id, true, false))
            assertFalse(LicensedTileVisibility.resolveVisibility(id, false, false))
        }
    }

    @Test fun unrelatedTilesAreNotRestrictedByFamilyPreferences() {
        assertTrue(LicensedTileVisibility.resolveVisibility("movies", false, false))
        assertTrue(LicensedTileVisibility.resolveVisibility("com.gmailorg.thedj", false, true))
    }
}
