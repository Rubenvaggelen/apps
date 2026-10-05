package com.gmailorg.runcoach

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/** Stuurt de live run-status naar het horloge (Galaxy Watch, Wear OS). */
object WearBridge {
    const val PATH_STATE = "/run/state"
    const val PATH_CMD = "/run/cmd"

    private var nodes: List<String> = emptyList()
    private var nodesFetchedAt = 0L
    private var lastSentAt = 0L
    private var lastStatus: RunStatus? = null

    /** Altijd vanaf de main thread aanroepen. */
    fun publish(context: Context, s: RunSnapshot, force: Boolean = false) {
        val ctx = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        val statusChanged = s.status != lastStatus
        if (!force && !statusChanged && now - lastSentAt < 2000) return
        lastSentAt = now
        lastStatus = s.status

        val refresh = now - nodesFetchedAt > 30_000 || (nodes.isEmpty() && now - nodesFetchedAt > 5_000)
        val data = encode(s)
        if (refresh || force) {
            nodesFetchedAt = now
            Wearable.getNodeClient(ctx).connectedNodes.addOnSuccessListener { list ->
                nodes = list.map { it.id }
                sendTo(ctx, data)
            }
        } else {
            sendTo(ctx, data)
        }
    }

    private fun sendTo(ctx: Context, data: ByteArray) {
        val mc = Wearable.getMessageClient(ctx)
        nodes.forEach { mc.sendMessage(it, PATH_STATE, data) }
    }

    private fun encode(s: RunSnapshot): ByteArray = JSONObject()
        .put("status", s.status.name)
        .put("d", s.distanceM)
        .put("t", s.elapsedMs)
        .put("p", s.currentPaceSecPerKm ?: -1.0)
        .put("a", s.avgPaceSecPerKm ?: -1.0)
        .put("g", s.goalPaceSecPerKm ?: -1)
        .put("s", if (s.stepsAvailable) s.steps else -1)
        .put("k", s.splits.size)
        .put("tg", s.targetDistanceM ?: -1.0)
        .toString()
        .toByteArray(Charsets.UTF_8)
}
