package com.gmailorg.hub

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Only Fitness preferences cross the signed Main/Run boundary. */
object FitnessTransferCodec {
    private val types = mapOf(
        "profile_set" to "boolean", "profile_sex" to "string", "profile_age" to "int",
        "profile_height_cm" to "int", "profile_weight_kg" to "float", "profile_goal" to "string",
        "profile_days_per_week" to "int", "profile_treadmill" to "boolean", "profile_gym" to "boolean",
        "profile_gym_weekdays" to "boolean", "profile_gym_weekend" to "boolean", "weight_date" to "string"
    )
    private fun type(key: String): String? {
        types[key]?.let { return it }
        if (!key.startsWith("completed_")) return null
        val date = key.removePrefix("completed_")
        return if (runCatching { LocalDate.parse(date).toString() == date }.getOrDefault(false)) "boolean" else null
    }
    fun encode(values: Map<String, *>): String {
        val rows = JSONArray()
        values.toSortedMap().forEach { (key, value) ->
            val expected = type(key) ?: return@forEach
            val valid = when (expected) {
                "boolean" -> value is Boolean
                "string" -> value is String
                "int" -> value is Int
                "float" -> value is Float && value.isFinite()
                else -> false
            }
            if (valid) rows.put(JSONObject().put("key", key).put("type", expected).put("value", value))
        }
        return JSONObject().put("version", 1).put("values", rows).toString()
    }
    fun decode(raw: String): Map<String, Any> {
        require(raw.length <= 256000) { "Fitness-overdracht te groot" }
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val rows = json.getJSONArray("values")
        return buildMap {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val key = row.getString("key")
                val expected = type(key) ?: continue
                require(row.getString("type") == expected)
                val value: Any = when (expected) {
                    "boolean" -> row.getBoolean("value")
                    "string" -> row.getString("value")
                    "int" -> row.getInt("value")
                    "float" -> row.getDouble("value").toFloat().also { require(it.isFinite()) }
                    else -> continue
                }
                put(key, value)
            }
        }
    }
}
