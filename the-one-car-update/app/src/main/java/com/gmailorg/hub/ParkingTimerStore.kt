package com.gmailorg.hub

import android.content.Context

/** Bewaart de ingestelde parkeer-eindtijd (epoch millis), indien aanwezig. */
object ParkingTimerStore {

    private const val PREFS = "parking_timer_prefs"
    private const val KEY_END_TIME = "end_time_millis"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): Long? {
        val value = prefs(context).getLong(KEY_END_TIME, -1L)
        return if (value > 0) value else null
    }

    fun set(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_END_TIME, millis).apply()
        if (CarRadioConnectionService.isRadioConnected()) CarRadioConnectionService.sendParkingSnapshot(context)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_END_TIME).apply()
        if (CarRadioConnectionService.isRadioConnected()) CarRadioConnectionService.sendParkingSnapshot(context)
    }
}
