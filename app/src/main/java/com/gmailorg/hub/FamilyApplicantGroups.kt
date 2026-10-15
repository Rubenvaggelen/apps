package com.gmailorg.hub

import java.util.Locale

/** A display grouping is NEVER a license. Every installation keeps its own rights. */
internal object FamilyApplicantGroups {
    private val apps = mapOf(
        "com.theone.cinema" to "Cinema",
        "com.theone.cinema.tv" to "Cinema",
        "com.theone.series" to "Series",
        "com.theone.series.tv" to "Series",
        "com.theone.tv.phone" to "TV",
        "com.theone.tv" to "TV"
    )
    data class Group(val label: String, val devices: List<MainRegisteredDevice>, val family: Boolean)

    fun isFamilyPackage(packageName: String): Boolean = packageName in apps

    fun appLabel(packageName: String): String = apps[packageName] ?: ""

    fun groupKey(personName: String, packageName: String, deviceId: String): String {
        val normalized = personName.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        // Include non-Family devices with the same applicant; access stays per device.
        return if (normalized.isNotBlank()) "person:$normalized" else "unnamed:$deviceId"
    }

    fun deviceLabel(device: MainRegisteredDevice): String {
        val model = device.name.substringAfter(" (", "").substringBeforeLast(")", "").trim()
        val readable = if (model.isNotBlank()) model else
            device.name.takeUnless { it.startsWith("com.") }?.trim().orEmpty()
                .ifBlank { device.platform.ifBlank { "Apparaat" } }
        val app = appLabel(device.familyPackage)
        return if (app.isNotBlank()) "$app • $readable" else readable
    }

    fun groupDevices(devices: List<MainRegisteredDevice>): List<Group> {
        val grouped = linkedMapOf<String, MutableList<MainRegisteredDevice>>()
        for (d in devices.sortedWith(compareBy(
            { it.personName.lowercase(Locale.ROOT) },
            { appLabel(it.familyPackage) },
            { it.name.lowercase(Locale.ROOT) },
            { it.id }
        ))) {
            grouped.getOrPut(groupKey(d.personName, d.familyPackage, d.id)) { mutableListOf() }.add(d)
        }
        return grouped.map { (key, items) ->
            val label = if (key.startsWith("person:"))
                items.first().personName.trim() else "Naam ontbreekt — " + deviceLabel(items.first())
            Group(label = label, devices = items, family = key.startsWith("person:"))
        }
    }
}
