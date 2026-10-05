package com.gmailorg.runcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Eén opgeslagen training; id = moment van afronden (ms sinds 1970). */
data class Session(val id: Long, val snap: RunSnapshot)

/** Geschiedenis van afgeronde trainingen, bewaard als JSON in de app-opslag. */
object History {
    private const val FILE = "history.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** Nieuwste training eerst. */
    @Synchronized
    fun load(ctx: Context): List<Session> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.sortedByDescending { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(ctx: Context, snap: RunSnapshot) {
        write(ctx, load(ctx) + Session(System.currentTimeMillis(), snap))
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        write(ctx, load(ctx).filter { it.id != id })
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    private fun write(ctx: Context, sessions: List<Session>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(toJson(it)) }
        file(ctx).writeText(arr.toString())
    }

    private fun toJson(s: Session): JSONObject {
        val splits = JSONArray()
        s.snap.splits.forEach {
            splits.put(JSONObject().put("km", it.km).put("splitMs", it.splitMs).put("totalMs", it.totalMs))
        }
        return JSONObject()
            .put("id", s.id)
            .put("distanceM", s.snap.distanceM)
            .put("elapsedMs", s.snap.elapsedMs)
            .put("steps", s.snap.steps)
            .put("stepsAvailable", s.snap.stepsAvailable)
            .put("targetM", s.snap.targetDistanceM ?: -1.0)
            .put("goalPace", s.snap.goalPaceSecPerKm ?: -1)
            .put("splits", splits)
    }

    private fun fromJson(o: JSONObject): Session {
        val arr = o.optJSONArray("splits") ?: JSONArray()
        val splits = (0 until arr.length()).map {
            val sp = arr.getJSONObject(it)
            Split(sp.getInt("km"), sp.getLong("splitMs"), sp.getLong("totalMs"))
        }
        return Session(
            o.getLong("id"),
            RunSnapshot(
                status = RunStatus.FINISHED,
                distanceM = o.getDouble("distanceM"),
                elapsedMs = o.getLong("elapsedMs"),
                steps = o.optInt("steps", 0),
                stepsAvailable = o.optBoolean("stepsAvailable", false),
                targetDistanceM = o.optDouble("targetM", -1.0).takeIf { it > 0 },
                goalPaceSecPerKm = o.optInt("goalPace", -1).takeIf { it > 0 },
                splits = splits
            )
        )
    }
}
