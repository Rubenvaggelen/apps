package com.gmailorg.hub

import java.util.Locale

/** Display-only aggregation. No permission is ever granted using a person's name. */
internal object FamilyAccessRequestGrouping {
    data class Summary(
        val notificationKey: String,
        val person: String,
        val scopes: String,
        val firstDeviceId: String,
        val requestedAt: String
    )

    fun group(requests: List<MainPendingAccessRequest>): List<Summary> {
        val buckets = linkedMapOf<String, MutableList<MainPendingAccessRequest>>()
        val familyScopes = setOf(
            MainDeviceRegistry.ACCESS_CINEMA,
            MainDeviceRegistry.ACCESS_SERIES,
            MainDeviceRegistry.ACCESS_TV,
            MainDeviceRegistry.ACCESS_USER_APP,
            MainDeviceRegistry.ACCESS_MEDIA_PLAYER_XXX
        )
        for (request in requests) {
            val isFamily = request.deviceId.startsWith("fam-") &&
                request.scope in familyScopes &&
                request.personName.trim().isNotEmpty()
            val key = if (isFamily)
                "person:" + request.personName.trim().replace(Regex("\\s+"), " ")
                    .lowercase(Locale.ROOT)
            else "request:" + request.deviceId + "|" + request.scope + "|" + request.requestedAt
            buckets.getOrPut(key) { mutableListOf() }.add(request)
        }
        return buckets.map { (key, items) ->
            val ordered = items.sortedWith(compareBy(
                { it.scope }, { it.deviceId }, { it.requestedAt }
            ))
            val person = items.first().personName.trim().ifBlank {
                items.first().deviceName.ifBlank { "Iemand" }
            }
            val scopes = ordered.map {
                when (it.scope) {
                    MainDeviceRegistry.ACCESS_USER_APP -> "The One"
                    MainDeviceRegistry.ACCESS_CINEMA -> "Cinema"
                    MainDeviceRegistry.ACCESS_SERIES -> "Series"
                    MainDeviceRegistry.ACCESS_TV -> "TV"
                    MainDeviceRegistry.ACCESS_MEDIA_PLAYER_XXX -> "Media Player XXX 🔒"
                    MainDeviceRegistry.ACCESS_MIXES -> "The One Mixes"
                    MainDeviceRegistry.ACCESS_SHARED -> "Shared Media"
                    MainDeviceRegistry.ACCESS_DJ -> "The One DJ"
                    MainDeviceRegistry.ACCESS_RUN -> "The One Run"
                    else -> it.scope
                }
            }.distinct().joinToString(", ")
            // Include each exact signed installation and scope in the notification
            // fingerprint, so an additional app refreshes one existing person's notice.
            val fingerprint = ordered.joinToString(";") {
                it.deviceId + "|" + it.scope + "|" + it.requestedAt
            }
            Summary(
                notificationKey = "$key|$fingerprint",
                person = person,
                scopes = scopes,
                firstDeviceId = items.first().deviceId,
                requestedAt = items.maxOfOrNull { it.requestedAt } ?: ""
            )
        }
    }
}
