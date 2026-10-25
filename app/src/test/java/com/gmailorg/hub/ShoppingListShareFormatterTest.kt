package com.gmailorg.hub

import org.junit.Assert.*
import org.junit.Test

class ShoppingListShareFormatterTest {
    @Test fun sharesOnlyUncheckedItemsAndPreservesOrder() {
        val items = listOf(
            ShoppingItem("1", " brood ", false),
            ShoppingItem("2", "melk", true),
            ShoppingItem("3", "eieren", false)
        )
        assertEquals("🛒 Boodschappenlijst\n\n☐ brood\n☐ eieren",
            ShoppingListShareFormatter.format(items))
        // Formatting never changes the actual shopping list.
        assertEquals(3, items.size)
        assertTrue(items[1].done)
    }

    @Test fun doesNotOfferShareWhenOnlyCompletedItemsExist() {
        assertNull(ShoppingListShareFormatter.format(listOf(ShoppingItem("1", "melk", true))))
        assertNull(ShoppingListShareFormatter.format(emptyList()))
    }

    @Test fun blankItemsAreIgnored() {
        assertNull(ShoppingListShareFormatter.format(listOf(ShoppingItem("1", " ", false))))
    }
}
