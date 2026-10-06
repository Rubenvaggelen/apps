package com.gmailorg.hub

/** Shared rules for geofence events and the current-location fallback. */
object SupermarketReminderPolicy {
    const val RADIUS_METERS = 180f
    const val COOLDOWN_MS = 15 * 60 * 1000L
    const val MAX_FIX_AGE_MS = 120000L

    fun mayDeliver(enabled: Boolean, pendingCount: Int, notificationsAllowed: Boolean,
                   lastDelivered: Long, now: Long): Boolean =
        enabled && pendingCount > 0 && notificationsAllowed &&
            (lastDelivered <= 0L || now < lastDelivered || now - lastDelivered >= COOLDOWN_MS)

    fun isNearby(distance: Float, accuracy: Float, ageMs: Long): Boolean =
        distance.isFinite() && distance >= 0f && distance <= RADIUS_METERS &&
            accuracy.isFinite() && accuracy > 0f && accuracy <= RADIUS_METERS &&
            ageMs in 0..MAX_FIX_AGE_MS
}
