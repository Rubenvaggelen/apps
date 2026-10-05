package com.gmailorg.runcoach

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coachniveaus:
 *  0 = Stil     -> alleen elke kilometer (+ doel gehaald)
 *  1 = Normaal  -> kilometerupdates + tempocorrecties (als doeltempo aan staat)
 *  2 = Veel     -> alles van Normaal + elke 500 m tempo-info + motivatie
 */
class Coach(private val speaker: Speaker) {

    companion object {
        private const val OFF_THRESHOLD = 12.0      // sec/km afwijking voordat de coach ingrijpt
        private const val BACK_THRESHOLD = 8.0      // sec/km marge om "weer op tempo" te zeggen
        private const val SUSTAIN_MS = 20_000L      // zo lang moet de afwijking aanhouden
        private const val BACK_SUSTAIN_MS = 15_000L
        private const val COOLDOWN_MS = 60_000L     // minimaal stilte na een correctie
        private const val WARMUP_MS = 60_000L       // eerste minuut geen correcties
    }

    private var level = 1
    private var goal: Int? = null
    private var target: Double? = null

    private var lastKm = 0
    private var lastHalf = 0
    private var offDir = 0
    private var offSince = 0L
    private var lastCorrection = -1_000_000L
    private var correctedDir = 0
    private var backSince = -1L
    private var halfwayDone = false
    private var lastKmDone = false
    private var finishDone = false
    private var motivationIdx = 0

    private val motivation = listOf(
        "Lekker bezig, blijf ontspannen ademen.",
        "Schouders los, armen soepel mee laten bewegen.",
        "Mooi ritme, hou dit vast.",
        "Je doet het goed, blijf gefocust.",
        "Korte, lichte passen. Je loopt sterk."
    )

    fun configure(level: Int, goal: Int?, target: Double?) {
        this.level = level
        this.goal = goal
        this.target = target
        lastKm = 0
        lastHalf = 0
        offDir = 0
        offSince = 0L
        lastCorrection = -1_000_000L
        correctedDir = 0
        backSince = -1L
        halfwayDone = false
        lastKmDone = false
        finishDone = false
        motivationIdx = 0
    }

    fun onStart(gpsFix: Boolean) {
        val sb = StringBuilder(
            if (gpsFix) "GPS gevonden. Succes!"
            else "We starten. Het GPS-signaal kan in het begin nog wat onnauwkeurig zijn."
        )
        target?.let { sb.append(" Doel: ${Fmt.spokenKm(it)}.") }
        goal?.let { sb.append(" Doeltempo ${Fmt.spokenPace(it.toDouble())}.") }
        speaker.say(sb.toString())
    }

    fun onPause() = speaker.say("Training gepauzeerd.")
    fun onResume() = speaker.say("We gaan weer verder.")

    fun onFinish(s: RunSnapshot) {
        val sb = StringBuilder("Training gestopt. Je liep ${Fmt.spokenKm(s.distanceM)} in ${Fmt.spokenTime(s.elapsedMs)}.")
        s.avgPaceSecPerKm?.let { sb.append(" Gemiddeld tempo ${Fmt.spokenPace(it)}.") }
        if (s.stepsAvailable && s.steps > 0) sb.append(" ${s.steps} stappen.")
        speaker.say(sb.toString())
    }

    fun onTick(s: RunSnapshot) {
        // 1. Doelafstand gehaald
        var finishedNow = false
        val t = target
        if (t != null && !finishDone && s.distanceM >= t) {
            finishDone = true
            finishedNow = true
            val avg = s.avgPaceSecPerKm?.let { Fmt.spokenPace(it) } ?: "onbekend"
            speaker.say(
                "Gefeliciteerd! ${Fmt.spokenKm(t)} voltooid in ${Fmt.spokenTime(s.elapsedMs)}. " +
                    "Gemiddeld tempo $avg. Je mag uitlopen of op stop drukken."
            )
        }

        // 2. Kilometer- en 500 meter-updates
        val km = s.splits.size
        if (km > lastKm) {
            lastKm = km
            lastHalf = km * 2
            if (!finishedNow) announceKm(km, s)
        } else if (level >= 2) {
            val halves = (s.distanceM / 500).toInt()
            if (halves > lastHalf) {
                lastHalf = halves
                if (halves % 2 == 1) announceHalf(halves, s)
            }
        }

        // 3. Halverwege / laatste kilometer
        if (t != null && !finishDone) {
            if (level >= 2 && !halfwayDone && t >= 2000 && s.distanceM >= t / 2) {
                halfwayDone = true
                speaker.say("Halverwege! Goed bezig, hou dit vast.")
            }
            if (level >= 1 && !lastKmDone && t > 1000 && s.distanceM >= t - 1000) {
                lastKmDone = true
                speaker.say("Nog één kilometer. Alles geven!")
            }
        }

        // 4. Tempocorrecties
        if (level >= 1 && goal != null) checkPace(s)
    }

    private fun announceKm(km: Int, s: RunSnapshot) {
        val sb = StringBuilder("$km kilometer. Tijd ${Fmt.spokenTime(s.elapsedMs)}.")
        s.avgPaceSecPerKm?.let { sb.append(" Gemiddeld tempo ${Fmt.spokenPace(it)}.") }
        val split = s.splits.lastOrNull()
        if (split != null && km > 1) sb.append(" Deze kilometer in ${Fmt.spokenTime(split.splitMs)}.")
        if (s.stepsAvailable && s.steps > 0) sb.append(" ${s.steps} stappen.")
        speaker.say(sb.toString())
    }

    private fun announceHalf(halves: Int, s: RunSnapshot) {
        val sb = StringBuilder("${Fmt.spokenKm(halves * 500.0)}.")
        val cur = s.currentPaceSecPerKm
        if (cur != null) sb.append(" Huidig tempo ${Fmt.spokenPace(cur)}.")
        val g = goal
        if (g != null && cur != null) {
            val d = (cur - g).roundToInt()
            sb.append(
                when {
                    abs(d) <= 5 -> " Precies op doeltempo."
                    d > 0 -> " $d seconden per kilometer langzamer dan je doel."
                    else -> " ${-d} seconden per kilometer sneller dan je doel."
                }
            )
        } else {
            sb.append(" ").append(motivation[motivationIdx % motivation.size])
            motivationIdx++
        }
        speaker.say(sb.toString())
    }

    private fun checkPace(s: RunSnapshot) {
        val g = goal ?: return
        val cur = s.currentPaceSecPerKm ?: return
        val now = s.elapsedMs
        if (now < WARMUP_MS || s.distanceM < 200) return

        val diff = cur - g
        val dir = when {
            diff > OFF_THRESHOLD -> 1      // te langzaam
            diff < -OFF_THRESHOLD -> -1    // te snel
            else -> 0
        }

        if (dir != 0) {
            backSince = -1L
            if (dir != offDir) {
                offDir = dir
                offSince = now
            }
            if (now - offSince >= SUSTAIN_MS && now - lastCorrection >= COOLDOWN_MS) {
                speaker.say(
                    if (dir == 1) "Iets versnellen, je loopt nu ${Fmt.spokenPace(cur)}."
                    else "Rustiger aan, je zit op ${Fmt.spokenPace(cur)}."
                )
                lastCorrection = now
                correctedDir = dir
                offSince = now
            }
        } else {
            offDir = 0
            if (correctedDir != 0 && abs(diff) <= BACK_THRESHOLD) {
                if (backSince < 0) backSince = now
                if (now - backSince >= BACK_SUSTAIN_MS) {
                    speaker.say("Goed zo, je zit weer op tempo.")
                    correctedDir = 0
                    backSince = -1L
                }
            } else {
                backSince = -1L
            }
        }
    }
}
