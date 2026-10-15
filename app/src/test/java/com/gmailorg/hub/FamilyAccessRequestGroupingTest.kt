package com.gmailorg.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FamilyAccessRequestGroupingTest {
    private fun request(id: String, name: String, scope: String) =
        MainPendingAccessRequest(id, name, "Android test", scope, "2026-10-15T12:00:00Z")

    @Test fun sameApplicantThreeAppsShowsOneNotification() {
        val requests = listOf(
            request("fam-a", "Anna de Vries", MainDeviceRegistry.ACCESS_CINEMA),
            request("fam-b", " anna  de vries ", MainDeviceRegistry.ACCESS_SERIES),
            request("fam-c", "Anna de Vries", MainDeviceRegistry.ACCESS_TV)
        )
        val groups = FamilyAccessRequestGrouping.group(requests)
        assertEquals(1, groups.size)
        assertEquals("Cinema, Series, TV", groups.single().scopes)
    }

    @Test fun individualAuthorizationIdentityNeverDisappears() {
        val original = listOf(
            request("fam-a", "Anna", MainDeviceRegistry.ACCESS_CINEMA),
            request("fam-b", "Anna", MainDeviceRegistry.ACCESS_TV)
        )
        val first = FamilyAccessRequestGrouping.group(original).single()
        val second = FamilyAccessRequestGrouping.group(original.dropLast(1)).single()
        assertNotEquals(first.notificationKey, second.notificationKey)
    }

    @Test fun unrelatedDevicesNeverCombine() {
        val groups = FamilyAccessRequestGrouping.group(listOf(
            request("windows-1", "Anna", MainDeviceRegistry.ACCESS_RUN),
            request("windows-2", "Anna", MainDeviceRegistry.ACCESS_RUN)
        ))
        assertEquals(2, groups.size)
    }

    @Test fun distinctApplicantsDoNotCombine() {
        val groups = FamilyAccessRequestGrouping.group(listOf(
            request("fam-1", "Anna", MainDeviceRegistry.ACCESS_CINEMA),
            request("fam-2", "Bram", MainDeviceRegistry.ACCESS_SERIES)
        ))
        assertEquals(2, groups.size)
    }
}
