package com.gmailorg.hub

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccessRequestDismissalTest {
    private fun request(key: String) = NotifItem(key, "the.one.access.requests", "The One Rechten",
        "Aanvraag", "Run", 1L, false, true, "access_request", "device")

    @Test fun oldPersistentRequestCanBeClearedAndDoesNotReappear() {
        val key = "theone-access|" + UUID.randomUUID() + "|run|old"
        NotifStore.addOrUpdate(request(key))
        NotifStore.clearAll()
        assertFalse(NotifStore.getAll().any { it.key == key })
        NotifStore.addOrUpdate(request(key))
        assertFalse(NotifStore.getAll().any { it.key == key })
        val fresh = key + "-new"
        NotifStore.addOrUpdate(request(fresh))
        assertTrue(NotifStore.getAll().any { it.key == fresh })
        NotifStore.removeWhere(true) { it.key == fresh }
    }

    @Test fun swipeDismissalRemembersTheExactRequest() {
        val key = "theone-access|" + UUID.randomUUID() + "|run|old"
        NotifStore.addOrUpdate(request(key))
        NotifStore.removeByKey(key)
        NotifStore.addOrUpdate(request(key))
        assertFalse(NotifStore.getAll().any { it.key == key })
    }

    @Test fun unrelatedPersistentNotificationsArePreserved() {
        val key = UUID.randomUUID().toString()
        NotifStore.addOrUpdate(request(key).copy(actionType = "other"))
        NotifStore.clearAll()
        assertTrue(NotifStore.getAll().any { it.key == key })
        NotifStore.removeByKey(key, true)
    }
}
