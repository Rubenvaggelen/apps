package com.gmailorg.hub

import org.junit.Assert.*
import org.junit.Test

class SupermarketReminderPolicyTest {
    @Test fun pendingItemsCanTriggerFirstReminder() {
        assertTrue(SupermarketReminderPolicy.mayDeliver(true, 2, true, 0, 100))
    }
    @Test fun disabledEmptyOrBlockedNeverNotify() {
        assertFalse(SupermarketReminderPolicy.mayDeliver(false, 2, true, 0, 100))
        assertFalse(SupermarketReminderPolicy.mayDeliver(true, 0, true, 0, 100))
        assertFalse(SupermarketReminderPolicy.mayDeliver(true, 2, false, 0, 100))
    }
    @Test fun enterDwellAndFallbackShareCooldown() {
        assertFalse(SupermarketReminderPolicy.mayDeliver(true, 2, true, 100, 101))
        assertFalse(SupermarketReminderPolicy.mayDeliver(true, 2, true, 100, 100 + SupermarketReminderPolicy.COOLDOWN_MS - 1))
        assertTrue(SupermarketReminderPolicy.mayDeliver(true, 2, true, 100, 100 + SupermarketReminderPolicy.COOLDOWN_MS))
    }
    @Test fun changedClockDoesNotBlockRemindersIndefinitely() {
        assertTrue(SupermarketReminderPolicy.mayDeliver(true, 2, true, 2000, 1000))
    }
    @Test fun fallbackRequiresFreshAccurateNearbyFix() {
        assertTrue(SupermarketReminderPolicy.isNearby(100f, 20f, 1000))
        assertTrue(SupermarketReminderPolicy.isNearby(180f, 180f, 120000))
        assertFalse(SupermarketReminderPolicy.isNearby(181f, 20f, 1000))
        assertFalse(SupermarketReminderPolicy.isNearby(100f, 181f, 1000))
        assertFalse(SupermarketReminderPolicy.isNearby(100f, 20f, 120001))
        assertFalse(SupermarketReminderPolicy.isNearby(100f, 20f, -1))
    }
    @Test fun invalidLocationValuesCannotTriggerFallback() {
        assertFalse(SupermarketReminderPolicy.isNearby(Float.NaN, 20f, 1000))
        assertFalse(SupermarketReminderPolicy.isNearby(100f, Float.POSITIVE_INFINITY, 1000))
        assertFalse(SupermarketReminderPolicy.isNearby(-1f, 20f, 1000))
        assertFalse(SupermarketReminderPolicy.isNearby(100f, 0f, 1000))
    }
}
