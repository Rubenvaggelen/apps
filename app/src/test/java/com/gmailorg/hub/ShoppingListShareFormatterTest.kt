package com.gmailorg.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListShareFormatterTest {
    @Test fun emptyListCannotSend() {
        assertEquals("", ShoppingListShareFormatter.format(emptyList()))
    }

    @Test fun pendingAndDoneItemsAreClearAndPreserveOrder() {
        val groceries = listOf(
            ShoppingItem("first", "Melk", false),
            ShoppingItem("second", "Brood", true),
            ShoppingItem("third", "Eieren", false)
        )
        val text = ShoppingListShareFormatter.format(groceries)
        assertEquals("🛒 Boodschappenlijst - The One\n\nNog kopen:\n☐ Melk\n☐ Eieren\n\nAfgevinkt:\n✓ Brood", text)
        assertFalse(groceries[0].done)
        assertTrue(groceries[1].done)
    }

    @Test fun sharesCompletedItemsEvenIfNothingIsPending() {
        val text = ShoppingListShareFormatter.format(listOf(ShoppingItem("1", " Koffie ", true)))
        assertEquals("🛒 Boodschappenlijst - The One\n\nAfgevinkt:\n✓ Koffie", text)
    }

    @Test fun itemInputCannotInjectExtraListLines() {
        val text = ShoppingListShareFormatter.format(listOf(ShoppingItem("1", "Havermout\n\nChocolade", false)))
        assertTrue(text.contains("☐ Havermout Chocolade"))
        assertFalse(text.contains("☐ Havermout\n\nChocolade"))
    }
}
