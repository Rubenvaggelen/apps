package com.gmailorg.hub

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccessRequestDismissalTest {
    private fun request(key: String) = NotifItem(key, "the.one.access.requests", "The One Rechten",
        "Aanvraag", "Run", 1L, false, true, "access_request", "device")

    @Test fun persistentRequestSurvivesClearAllAndCanBeResolved() {
        val key = "theone-access|" + UUID.randomUUID() + "|run|old"
        NotifStore.addOrUpdate(request(key))
        NotifStore.clearAll()
        assertTrue(NotifStore.getAll().any { it.key == key })
        NotifStore.addOrUpdate(request(key))
        assertTrue(NotifStore.getAll().any { it.key == key })
        val fresh = key + "-new"
        NotifStore.addOrUpdate(request(fresh))
        assertTrue(NotifStore.getAll().any { it.key == fresh })
        NotifStore.removeWhere(true) { it.key == fresh }
    }

    @Test fun swipeCannotDismissPendingRequest() {
        val key = "theone-access|" + UUID.randomUUID() + "|run|old"
        NotifStore.addOrUpdate(request(key))
        NotifStore.removeByKey(key)
        assertTrue(NotifStore.getAll().any { it.key == key })
        NotifStore.removeWhere(includePersistent = true) { it.key == key }
        assertFalse(NotifStore.getAll().any { it.key == key })
    }

    @Test fun loginRequestCannotBeSwipedOrCleared() {
        val key = "theone-license|" + UUID.randomUUID()
        val notification = request(key).copy(actionType = "license_request")
        NotifStore.addOrUpdate(notification)
        NotifStore.removeByKey(key, force = true)
        NotifStore.clearAll()
        assertTrue(NotifStore.getAll().any { it.key == key })
        NotifStore.removeWhere(includePersistent = true) { it.key == key }
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
