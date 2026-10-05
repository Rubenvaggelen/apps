package com.gmailorg.runcoach

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Fmt {
    private val NL: Locale = Locale.forLanguageTag("nl-NL")

    // ---------- Scherm ----------

    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm <= 0 || secPerKm >= 3600) return "--:--"
        val t = secPerKm.roundToInt()
        return String.format(NL, "%d:%02d", t / 60, t % 60)
    }

    fun time(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(NL, "%d:%02d:%02d", h, m, sec)
        else String.format(NL, "%02d:%02d", m, sec)
    }

    fun km(meters: Double): String = String.format(NL, "%.2f", meters / 1000.0)

    fun speed(kmh: Double?): String = if (kmh == null) "--" else String.format(NL, "%.1f", kmh)

    // ---------- Spraak ----------

    fun spokenKm(meters: Double): String {
        val km = meters / 1000.0
        val r = Math.round(km)
        if (abs(km - r) < 0.005) return "$r kilometer"
        return String.format(NL, "%.2f", km).trimEnd('0').trimEnd(',') + " kilometer"
    }

    fun spokenPace(secPerKm: Double): String {
        val t = secPerKm.roundToInt()
        val m = t / 60
        val s = t % 60
        return if (s == 0) "$m minuten per kilometer" else "$m minuten $s per kilometer"
    }

    fun spokenTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h uur"
        if (m > 0) parts += if (m == 1L) "1 minuut" else "$m minuten"
        if (s > 0) parts += if (s == 1L) "1 seconde" else "$s seconden"
        return when (parts.size) {
            0 -> "0 seconden"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " en " + parts.last()
        }
    }
}
