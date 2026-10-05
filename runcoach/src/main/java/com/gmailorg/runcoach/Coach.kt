package com.gmailorg.runcoach

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coachniveaus:
 *  0 = Stil     -> alleen elke kilometer (+ doel gehaald)
 *  1 = Normaal  -> kilometerupdates + tempocorrecties (als doeltempo aan staat)
 *  2 = Veel     -> alles van Normaal + elke 500 m tempo-info + motivatie
 *
 * Stemmen: Rustig, Normaal of Streng (een strenge motivator die je bij elke kilometer aanpakt).
 */
class Coach(private val speaker: Speaker) {

    companion object {
        const val VOICE_CALM = 0
        const val VOICE_NORMAL = 1
        const val VOICE_STRICT = 2

        fun voiceSample(voice: Int) = when (voice) {
            VOICE_CALM -> "Hallo, ik ben je rustige coach. We lopen ontspannen en op gevoel."
            VOICE_STRICT -> "Luister goed. Ik ben je strenge motivator. Geen excuses, geen smoesjes. Lopen!"
            else -> "Hallo, ik ben je coach. Ik houd je op de hoogte van je afstand en je tempo."
        }

        private const val OFF_THRESHOLD = 12.0      // sec/km afwijking voordat de coach ingrijpt
        private const val BACK_THRESHOLD = 8.0      // sec/km marge om "weer op tempo" te zeggen
        private const val SUSTAIN_MS = 20_000L      // zo lang moet de afwijking aanhouden
        private const val BACK_SUSTAIN_MS = 15_000L
        private const val COOLDOWN_MS = 60_000L     // minimaal stilte na een correctie
        private const val WARMUP_MS = 60_000L       // eerste minuut geen correcties
    }

    private var level = 1
    private var voice = VOICE_NORMAL
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

    private val motivationNormal = listOf(
        "Lekker bezig, blijf ontspannen ademen.",
        "Schouders los, armen soepel mee laten bewegen.",
        "Mooi ritme, hou dit vast.",
        "Je doet het goed, blijf gefocust.",
        "Korte, lichte passen. Je loopt sterk."
    )

    private val motivationCalm = listOf(
        "Rustig ademen, je doet het prima.",
        "Geniet van je loop, je hoeft niets te bewijzen.",
        "Ontspan je schouders en laat je armen los meebewegen.",
        "Mooi zo, stap voor stap.",
        "Je loopt heerlijk, blijf lekker in je eigen ritme."
    )

    private val motivationStrict = listOf(
        "Niet verslappen! Je bent hier om te werken.",
        "Geen excuses. Doorlopen!",
        "Moe zijn telt niet. Tempo houden!",
        "Je kunt veel meer dan dit. Laat het zien!",
        "Hoofd omhoog, borst vooruit, doorgaan!",
        "Niemand zei dat het makkelijk was. Door!",
        "Opgeven is geen optie. Bijten!",
        "Dit is het moment waarop anderen stoppen. Jij niet!"
    )

    private fun v(calm: String, normal: String, strict: String) = when (voice) {
        VOICE_CALM -> calm
        VOICE_STRICT -> strict
        else -> normal
    }

    private fun nextMotivation(): String {
        val list = when (voice) {
            VOICE_CALM -> motivationCalm
            VOICE_STRICT -> motivationStrict
            else -> motivationNormal
        }
        return list[motivationIdx++ % list.size]
    }

    fun configure(level: Int, goal: Int?, target: Double?, voice: Int = VOICE_NORMAL) {
        this.level = level
        this.voice = voice
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

    fun onStart(gpsFix: Boolean, treadmill: Boolean = false) {
        val sb = StringBuilder(
            if (treadmill) "Loopbandtraining gestart. De afstand wordt berekend met de snelheid die je hebt ingevoerd."
            else if (gpsFix) v("GPS gevonden. Veel plezier!", "GPS gevonden. Succes!", "GPS gevonden. Lopen, nu!")
            else "We starten. Het GPS-signaal kan in het begin nog wat onnauwkeurig zijn."
        )
        target?.let { sb.append(" Doel: ${Fmt.spokenKm(it)}.") }
        goal?.let { sb.append(" Doeltempo ${Fmt.spokenPace(it.toDouble())}.") }
        speaker.say(sb.toString())
    }

    fun onPause() = speaker.say(
        v("Training gepauzeerd. Neem rustig je tijd.", "Training gepauzeerd.", "Pauze. Maak het kort, we zijn nog niet klaar.")
    )

    fun onResume() = speaker.say(
        v("We gaan rustig weer verder.", "We gaan weer verder.", "Genoeg gerust. Lopen!")
    )

    fun onFinish(s: RunSnapshot) {
        val sb = StringBuilder("Training gestopt. Je liep ${Fmt.spokenKm(s.distanceM)} in ${Fmt.spokenTime(s.elapsedMs)}.")
        s.avgPaceSecPerKm?.let { sb.append(" Gemiddeld tempo ${Fmt.spokenPace(it)}.") }
        if (s.stepsAvailable && s.steps > 0) sb.append(" ${s.steps} stappen.")
        sb.append(
            v(" Mooi gedaan, wees trots op jezelf.", " Goed gedaan!", " Niet slecht. Volgende keer verder en harder.")
        )
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
                v("Wat goed van je!", "Gefeliciteerd!", "Doel gehaald. Zo doe je dat!") + " ${Fmt.spokenKm(t)} voltooid in ${Fmt.spokenTime(s.elapsedMs)}. " +
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
                speaker.say(
                    v(
                        "Je bent halverwege. Blijf lekker ontspannen lopen.",
                        "Halverwege! Goed bezig, hou dit vast.",
                        "Halverwege. Het zware deel begint nu. Niet inhouden!"
                    )
                )
            }
            if (level >= 1 && !lastKmDone && t > 1000 && s.distanceM >= t - 1000) {
                lastKmDone = true
                speaker.say(
                    v(
                        "Nog één kilometer. Je bent er bijna.",
                        "Nog één kilometer. Alles geven!",
                        "Nog één kilometer. Nu ga je helemaal leeg. Alles eruit!"
                    )
                )
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
        // de strenge motivator pakt je bij elke kilometer aan
        if (voice == VOICE_STRICT && level >= 1) sb.append(" ").append(nextMotivation())
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
            if (voice == VOICE_STRICT) sb.append(" ").append(nextMotivation())
        } else {
            sb.append(" ").append(nextMotivation())
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
                    if (dir == 1) v(
                        "Je mag iets versnellen als het lukt, je loopt nu ${Fmt.spokenPace(cur)}.",
                        "Iets versnellen, je loopt nu ${Fmt.spokenPace(cur)}.",
                        "Te langzaam! ${Fmt.spokenPace(cur)} is niet goed genoeg. Versnellen, nu!"
                    )
                    else v(
                        "Doe maar iets rustiger, je zit op ${Fmt.spokenPace(cur)}.",
                        "Rustiger aan, je zit op ${Fmt.spokenPace(cur)}.",
                        "Te snel, ${Fmt.spokenPace(cur)}. Houd je aan het plan en neem gas terug!"
                    )
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
                    speaker.say(
                        v("Mooi, je zit weer op tempo.", "Goed zo, je zit weer op tempo.", "Zo ja. En nu vasthouden!")
                    )
                    correctedDir = 0
                    backSince = -1L
                }
            } else {
                backSince = -1L
            }
        }
    }
}
