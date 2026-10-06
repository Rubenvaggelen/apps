package com.gmailorg.hub

import org.junit.Assert.*
import org.junit.Test

class NotificationDismissalsTest {
    private fun cast(time: Long = 100) = NotifItem("ziggo-key", "com.lgi.ziggotv",
        "Ziggo GO", "Chains", "Casten naar Kantoor 2", time, false, ongoing = true)

    @Test fun clearHidesCastReplaysAndTimestampRefreshesAcrossRestart() {
        val state = NotificationDismissals()
        state.remember(cast())
        assertTrue(state.shouldSuppress(cast()))
        val restored = NotificationDismissals(state.snapshot())
        assertTrue(restored.shouldSuppress(cast(500)))
    }

    @Test fun changedContentIsANewVisibleNotification() {
        val state = NotificationDismissals()
        state.remember(cast())
        assertFalse(state.shouldSuppress(cast().copy(title = "Nieuw programma")))
        assertFalse(state.shouldSuppress(cast()))
    }

    @Test fun legacyStoredCastWithoutOngoingFlagStillStaysDismissed() {
        val state = NotificationDismissals()
        state.remember(cast().copy(ongoing = false))
        assertTrue(state.shouldSuppress(cast(500)))
    }

    @Test fun newSessionAfterSourceRemovalCanHaveSameTitleAndText() {
        val state = NotificationDismissals()
        state.remember(cast())
        state.sourceRemoved("ziggo-key")
        assertFalse(state.shouldSuppress(cast(500)))
    }

    @Test fun ordinaryMessagesWithIdenticalBodyAtNewTimeRemainVisible() {
        val message = cast().copy(packageName = "com.whatsapp", ongoing = false)
        val state = NotificationDismissals()
        state.remember(message)
        assertTrue(state.shouldSuppress(message))
        assertFalse(state.shouldSuppress(message.copy(postTime = 200)))
    }

    @Test fun dismissalOnlyAppliesToItsOwnKeyAndPackage() {
        val state = NotificationDismissals()
        state.remember(cast())
        assertFalse(state.shouldSuppress(cast().copy(key = "other")))
        assertFalse(state.shouldSuppress(cast().copy(packageName = "other.app")))
    }
}
