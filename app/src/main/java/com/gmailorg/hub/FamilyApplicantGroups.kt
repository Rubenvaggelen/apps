package com.gmailorg.hub

import java.util.Locale

/** UI grouping only. Rights and approvals stay bound to each original device ID. */
internal object FamilyApplicantGroups {
    private val packageNames = setOf(
        "com.theone.cinema", "com.theone.cinema.tv",
        "com.theone.series", "com.theone.series.tv",
        "com.theone.tv.phone", "com.theone.tv"
    )

    data class Group(val label: String, val devices: List<MainRegisteredDevice>, val family: Boolean)

    fun isFamilyPackage(packageName: String): Boolean = packageName in packageNames

    fun groupKey(personName: String, packageName: String, deviceId: String): String {
        val cleaned = personName.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        return if (isFamilyPackage(packageName) && cleaned.isNotEmpty())
            "family-person:$cleaned" else "device:$deviceId"
    }

    fun groupDevices(devices: List<MainRegisteredDevice>): List<Group> {
        val collected = linkedMapOf<String, MutableList<MainRegisteredDevice>>()
        for (device in devices.sortedWith(compareBy(
            { it.personName.lowercase(Locale.ROOT) },
            { it.name.lowercase(Locale.ROOT) },
            { it.id }
        ))) {
            val key = groupKey(device.personName, device.familyPackage, device.id)
            collected.getOrPut(key) { mutableListOf() }.add(device)
        }
        return collected.map { (key, grouped) ->
            val family = key.startsWith("family-person:")
            val label = if (family) {
                val person = grouped.first().personName.trim()
                val apps = grouped.mapNotNull {
                    when (it.familyPackage) {
                        "com.theone.cinema", "com.theone.cinema.tv" -> "Cinema"
                        "com.theone.series", "com.theone.series.tv" -> "Series"
                        "com.theone.tv.phone", "com.theone.tv" -> "TV"
                        else -> null
                    }
                }.distinct().joinToString(", ")
                "$person — $apps"
            } else {
                val device = grouped.single()
                val person = device.personName.trim()
                device.name.ifBlank { "Apparaat" } +
                    if (person.isNotEmpty() && person != device.name) " — $person" else ""
            }
            Group(label, grouped, family)
        }
    }
}
