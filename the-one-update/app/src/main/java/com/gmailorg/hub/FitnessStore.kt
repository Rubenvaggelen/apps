package com.gmailorg.hub

import android.content.Context
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

object FitnessStore {
    private const val PREFS = "fitness_progress"
    private const val KEY_PROFILE_SET = "profile_set"
    private const val KEY_SEX = "profile_sex"
    private const val KEY_AGE = "profile_age"
    private const val KEY_HEIGHT = "profile_height_cm"
    private const val KEY_WEIGHT = "profile_weight_kg"
    private const val KEY_GOAL = "profile_goal"
    private const val KEY_DAYS = "profile_days_per_week"
    private const val KEY_TREADMILL = "profile_treadmill"
    private const val KEY_GYM = "profile_gym"
    private const val KEY_GYM_WEEKDAYS = "profile_gym_weekdays"
    private const val KEY_GYM_WEEKEND = "profile_gym_weekend"
    private const val KEY_WEIGHT_DATE = "weight_date"
    private const val KEY_COMPLETED_PREFIX = "completed_"

    data class Profile(
        val sex: String,
        val age: Int,
        val heightCm: Int,
        val weightKg: Float,
        val goal: String,
        val daysPerWeek: Int,
        val hasTreadmill: Boolean,
        val hasGym: Boolean,
        val gymWeekdays: Boolean,
        val gymWeekend: Boolean
    )

    data class Progress(
        val completedCoreSessions: Int,
        val targetCoreSessions: Int,
        val completedDates: List<LocalDate>,
        val latestWeightKg: Float,
        val weightDate: LocalDate?
    )

    fun hasProfile(context: Context): Boolean = prefs(context).getBoolean(KEY_PROFILE_SET, false)

    fun loadProfile(context: Context): Profile? {
        if (!hasProfile(context)) return null
        val p = prefs(context)
        return Profile(
            sex = p.getString(KEY_SEX, "Niet opgegeven") ?: "Niet opgegeven",
            age = p.getInt(KEY_AGE, 40),
            heightCm = p.getInt(KEY_HEIGHT, 175),
            weightKg = p.getFloat(KEY_WEIGHT, 75f),
            goal = p.getString(KEY_GOAL, "Conditie + spiermassa") ?: "Conditie + spiermassa",
            daysPerWeek = p.getInt(KEY_DAYS, 4).coerceIn(2, 6),
            hasTreadmill = p.getBoolean(KEY_TREADMILL, false),
            hasGym = p.getBoolean(KEY_GYM, false),
            // Existing profiles pre-date the availability switches. If a gym was
            // already enabled, keep the old behaviour until the user edits it.
            gymWeekdays = p.getBoolean(KEY_GYM_WEEKDAYS, p.getBoolean(KEY_GYM, false)),
            gymWeekend = p.getBoolean(KEY_GYM_WEEKEND, p.getBoolean(KEY_GYM, false))
        )
    }

    fun saveProfile(context: Context, profile: Profile) {
        prefs(context).edit()
            .putBoolean(KEY_PROFILE_SET, true)
            .putString(KEY_SEX, profile.sex)
            .putInt(KEY_AGE, profile.age)
            .putInt(KEY_HEIGHT, profile.heightCm)
            .putFloat(KEY_WEIGHT, profile.weightKg)
            .putString(KEY_GOAL, profile.goal)
            .putInt(KEY_DAYS, profile.daysPerWeek.coerceIn(2, 6))
            .putBoolean(KEY_TREADMILL, profile.hasTreadmill)
            .putBoolean(KEY_GYM, profile.hasGym)
            .putBoolean(KEY_GYM_WEEKDAYS, profile.hasGym && profile.gymWeekdays)
            .putBoolean(KEY_GYM_WEEKEND, profile.hasGym && profile.gymWeekend)
            .putString(KEY_WEIGHT_DATE, LocalDate.now().toString())
            .apply()
    }

    fun isCompleted(context: Context, date: LocalDate = LocalDate.now()): Boolean =
        prefs(context).getBoolean(KEY_COMPLETED_PREFIX + date, false)

    fun setCompleted(context: Context, completed: Boolean, date: LocalDate = LocalDate.now()) {
        prefs(context).edit().putBoolean(KEY_COMPLETED_PREFIX + date, completed).apply()
    }

    fun saveWeight(context: Context, weightKg: Float) {
        if (weightKg !in 35f..250f) return
        prefs(context).edit()
            .putFloat(KEY_WEIGHT, weightKg)
            .putString(KEY_WEIGHT_DATE, LocalDate.now().toString())
            .apply()
    }

    fun latestWeight(context: Context): Float = loadProfile(context)?.weightKg ?: 0f

    fun progress(context: Context, today: LocalDate = LocalDate.now()): Progress {
        val profile = loadProfile(context)
        val target = profile?.daysPerWeek ?: 0
        val wf = WeekFields.of(Locale.getDefault())
        val week = today.get(wf.weekOfWeekBasedYear())
        val weekYear = today.get(wf.weekBasedYear())
        val completed = (0L..13L)
            .map { today.minusDays(it) }
            .filter { date ->
                date.get(wf.weekOfWeekBasedYear()) == week &&
                    date.get(wf.weekBasedYear()) == weekYear &&
                    isCompleted(context, date)
            }
            .sorted()
        val weightDate = prefs(context).getString(KEY_WEIGHT_DATE, null)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        return Progress(
            completedCoreSessions = completed.size.coerceAtMost(target),
            targetCoreSessions = target,
            completedDates = completed,
            latestWeightKg = latestWeight(context),
            weightDate = weightDate
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
