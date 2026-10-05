package com.gmailorg.runcoach

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class RunStatus { IDLE, WAITING_GPS, RUNNING, PAUSED, FINISHED }

/** Eén afgeronde kilometer: tijd van die kilometer en totale tijd op dat moment. */
data class Split(val km: Int, val splitMs: Long, val totalMs: Long)

data class RunSnapshot(
    val status: RunStatus = RunStatus.IDLE,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0L,
    val currentPaceSecPerKm: Double? = null,
    val steps: Int = 0,
    val cadence: Int? = null,
    val splits: List<Split> = emptyList(),
    val gpsAccuracyM: Float? = null,
    val targetDistanceM: Double? = null,
    val goalPaceSecPerKm: Int? = null,
    val coachLevel: Int = 1,
    val stepsAvailable: Boolean = false,
    val treadmill: Boolean = false,
    val treadmillSpeedKmh: Double? = null
) {
    val avgPaceSecPerKm: Double?
        get() = if (distanceM >= 50) (elapsedMs / 1000.0) / (distanceM / 1000.0) else null

    val avgSpeedKmh: Double?
        get() = if (elapsedMs >= 5_000) (distanceM / 1000.0) / (elapsedMs / 3_600_000.0) else null

    val avgCadence: Int?
        get() = if (elapsedMs >= 60_000 && steps > 0) (steps / (elapsedMs / 60_000.0)).toInt() else null
}

/** Simpele gedeelde opslag tussen de service en het scherm (zelfde proces). */
object RunRepository {
    @Volatile
    var snapshot: RunSnapshot = RunSnapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(RunSnapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(s: RunSnapshot) {
        snapshot = s
        main.post { listeners.forEach { it(s) } }
    }

    fun addListener(l: (RunSnapshot) -> Unit) { listeners.add(l) }
    fun removeListener(l: (RunSnapshot) -> Unit) { listeners.remove(l) }
}
