package com.gmailorg.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListShareFormatterTest {
    @Test fun emptyListIsNotShared() {
        assertNull(ShoppingListShareFormatter.format(emptyList()))
        assertNull(ShoppingListShareFormatter.format(listOf(ShoppingItem("1", "   "))))
    }

    @Test fun pendingItemsRemainInSavedOrder() {
        val value = ShoppingListShareFormatter.format(listOf(
            ShoppingItem("1", "Brood"), ShoppingItem("2", "Melk")
        ))!!
        assertEquals("*Boodschappenlijst* 🛒\n\n*Nog halen:*\n☐ Brood\n☐ Melk", value)
    }

    @Test fun checkedItemsStayVisibleWithoutChangingUnderlyingList() {
        val items = listOf(
            ShoppingItem("1", "Eieren", done = true),
            ShoppingItem("2", "Bananen", done = false),
            ShoppingItem("3", "Koffie", done = true)
        )
        val value = ShoppingListShareFormatter.format(items)!!
        assertTrue(value.contains("*Nog halen:*\n☐ Bananen"))
        assertTrue(value.contains("*Afgevinkt:*\n☑ Eieren\n☑ Koffie"))
        assertTrue(items[0].done)
        assertFalse(items[1].done)
        assertEquals(3, items.size)
    }

    @Test fun checkedOnlyAndNewLinesAreSupported() {
        val value = ShoppingListShareFormatter.format(listOf(
            ShoppingItem("1", "Olijfolie\nextra vierge", done = true)
        ))!!
        assertFalse(value.contains("*Nog halen:*"))
        assertTrue(value.contains("☑ Olijfolie extra vierge"))
    }
}
