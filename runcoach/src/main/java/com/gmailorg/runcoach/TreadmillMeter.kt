package com.gmailorg.runcoach

/** Distance from active workout time. Paused wall time is never passed here. */
class TreadmillMeter(initialSpeedKmh: Double) {
    var speedKmh: Double = valid(initialSpeedKmh)
        private set
    var distanceM: Double = 0.0
        private set
    val kilometerTimes = mutableListOf<Long>()
    private var lastActiveMs = 0L
    fun advance(activeMs: Long): Double {
        require(activeMs >= lastActiveMs)
        val nextDistance = distanceM + speedKmh * (activeMs - lastActiveMs) / 3600.0
        while (speedKmh > 0 && nextDistance >= (kilometerTimes.size + 1) * 1000.0) {
            val boundary = (kilometerTimes.size + 1) * 1000.0
            kilometerTimes.add(lastActiveMs + kotlin.math.round((boundary - distanceM) * 3600.0 / speedKmh).toLong())
        }
        distanceM = nextDistance
        lastActiveMs = activeMs
        return distanceM
    }
    fun changeSpeed(speed: Double, activeMs: Long) {
        val checked = valid(speed)
        advance(activeMs)
        speedKmh = checked
    }
    private fun valid(speed: Double): Double {
        require(speed.isFinite() && speed in 0.0..40.0)
        return speed
    }
}
