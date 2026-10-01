package com.gmailorg.hub

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class DailyMotivationWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val context = applicationContext
        NotifStore.init(context)

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            .format(System.currentTimeMillis())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (prefs.getString(KEY_LAST_DATE, "") != today) {
            val calendar = Calendar.getInstance()
            val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
            val quote = QUOTES[(dayOfYear - 1).mod(QUOTES.size)]

            NotifStore.addOrUpdate(
                NotifItem(
                    key = "theone-motivation|$today",
                    packageName = "the.one.daily.motivation",
                    appLabel = "The One Daily",
                    title = "Goedemorgen ☀️",
                    text = quote,
                    postTime = System.currentTimeMillis(),
                    hasReplyAction = false,
                    persistent = false,
                    actionType = "daily_motivation",
                    actionValue = today
                )
            )
            prefs.edit().putString(KEY_LAST_DATE, today).apply()
        }

        scheduleNext(context)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "the-one-daily-motivation-0900"
        private const val PREFS = "daily_motivation"
        private const val KEY_LAST_DATE = "last_date"

        private val QUOTES = listOf(
            "Je hoeft vandaag niet alles te kunnen. Eén goede stap is genoeg om vooruit te gaan.",
            "Begin rustig, kies wat belangrijk is en maak daar iets moois van.",
            "Een kleine overwinning in de ochtend kan de toon zetten voor je hele dag.",
            "Je toekomst wordt gebouwd met de keuzes die je vandaag wél maakt.",
            "Geef je energie aan wat je kunt veranderen en laat de rest even liggen.",
            "Vandaag hoeft niet perfect te zijn om een goede dag te worden.",
            "Doe iets waar je vanavond trots op kunt terugkijken.",
            "Rust in je hoofd begint vaak met één duidelijke volgende stap.",
            "Je hoeft niemand in te halen. Zorg alleen dat jij blijft bewegen.",
            "Maak ruimte voor iets kleins waar je vandaag blij van wordt.",
            "Een moeilijke ochtend zegt niets over hoe mooi de rest van je dag kan worden.",
            "Blijf dichtbij jezelf; daar maak je meestal je beste keuzes.",
            "Wat vandaag langzaam gaat, kan nog steeds vooruitgang zijn.",
            "Je hebt niet meer motivatie nodig dan nodig is voor de eerste stap.",
            "Laat vandaag niet bepalen door gisteren. Je begint opnieuw vanaf hier.",
            "Goed voor jezelf zorgen is óók productief.",
            "Kies vandaag één ding dat echt telt en geef dat je beste aandacht.",
            "Je hoeft groot te beginnen om iets groots op te bouwen.",
            "Een frisse start hoeft niet maandag te zijn. Vandaag werkt ook.",
            "Wees trots op wat je al hebt volgehouden terwijl niemand het zag.",
            "Soms is vooruitgang gewoon: doorgaan zonder jezelf voorbij te lopen.",
            "Maak vandaag lichter door niet alles tegelijk te dragen.",
            "Je beste tempo is het tempo dat je vol kunt houden.",
            "Er zit veel kracht in rustig weten waar je naartoe wilt.",
            "Gebruik je ochtend niet om te twijfelen aan jezelf, maar om te beginnen.",
            "Je mag vandaag kiezen voor plezier én vooruitgang.",
            "Een goede dag begint niet met geluk, maar vaak met aandacht.",
            "Geef jezelf dezelfde aanmoediging die je iemand anders zou geven.",
            "Vandaag is weer een kans om iets beter, fijner of mooier te maken.",
            "Laat één tegenvaller niet beslissen hoe de rest van je dag voelt.",
            "Je hoeft maar één keer vaker op te staan dan je wordt tegengehouden."
        )

        fun schedule(context: Context) {
            scheduleNext(context.applicationContext)
        }

        private fun scheduleNext(context: Context) {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val delay = (next.timeInMillis - now.timeInMillis).coerceAtLeast(1L)
            val request = OneTimeWorkRequestBuilder<DailyMotivationWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_WORK,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
