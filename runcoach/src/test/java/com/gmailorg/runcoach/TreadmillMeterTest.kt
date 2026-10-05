package com.gmailorg.runcoach

import org.junit.Assert.*
import org.junit.Test

class TreadmillMeterTest {
    @Test fun constantSpeedAndDelayedTicks() {
        val meter = TreadmillMeter(12.0)
        assertEquals(2500.0, meter.advance(750000), 0.00001)
        assertEquals(listOf(300000L, 600000L), meter.kilometerTimes)
    }
    @Test fun speedChangesUseOldSpeedForPreviousTime() {
        val meter = TreadmillMeter(6.0)
        meter.changeSpeed(12.0, 600000)
        assertEquals(2000.0, meter.advance(900000), 0.00001)
        assertEquals(listOf(600000L, 900000L), meter.kilometerTimes)
    }
    @Test fun pauseDoesNotCountWallTimeAndResumeContinues() {
        val meter = TreadmillMeter(6.0)
        meter.advance(60000)
        assertEquals(100.0, meter.advance(60000), 0.00001)
        meter.changeSpeed(12.0, 60000)
        assertEquals(300.0, meter.advance(120000), 0.00001)
    }
    @Test fun zeroSpeedAndRestart() {
        val meter = TreadmillMeter(0.0)
        assertEquals(0.0, meter.advance(300000), 0.0)
        meter.changeSpeed(10.0, 300000)
        assertEquals(1000.0, meter.advance(660000), 0.00001)
        assertEquals(listOf(660000L), meter.kilometerTimes)
    }
    @Test fun repeatedTimestampCannotDoubleCount() {
        val meter = TreadmillMeter(8.0)
        val first = meter.advance(450000)
        repeat(10) { assertEquals(first, meter.advance(450000), 0.0) }
        assertEquals(listOf(450000L), meter.kilometerTimes)
    }
    @Test fun invalidSpeedDoesNotMutateDistance() {
        val meter = TreadmillMeter(8.0)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 41.0)) {
            try { meter.changeSpeed(bad, 450000); fail("Invalid speed accepted") }
            catch (_: IllegalArgumentException) {}
        }
        assertEquals(0.0, meter.distanceM, 0.0)
        assertEquals(1000.0, meter.advance(450000), 0.00001)
    }
    @Test(expected = IllegalArgumentException::class)
    fun timeCannotGoBackwards() {
        val meter = TreadmillMeter(6.0)
        meter.advance(60000)
        meter.advance(50000)
    }
}
