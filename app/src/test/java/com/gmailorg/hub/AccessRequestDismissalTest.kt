package com.gmailorg.hub

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccessRequestDismissalTest {
    private fun request(key: String, text: String = "Run") = NotifItem(
        key, "the.one.access.requests", "The One Rechten",
        "Aanvraag", text, 1L, false, true, "access_request", "person|parent"
    )

    @Test fun groupedRequestStaysUntilTheServerConfirmsItWasResolved() {
        val key = "theone-access|person|" + UUID.randomUUID()
        NotifStore.addOrUpdate(request(key))
        NotifStore.clearAll()
        assertTrue(NotifStore.getAll().any { it.key == key })

        NotifStore.removeByKey(key)
        assertTrue(NotifStore.getAll().any { it.key == key })

        NotifStore.addOrUpdate(request(key, "Run, Kids"))
        assertEquals("Run, Kids", NotifStore.getAll().first { it.key == key }.text)

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
