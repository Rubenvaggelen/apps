#!/usr/bin/env python3
"""Group only Family access UI by applicant. Preserve all per-device permission calls."""
from pathlib import Path
p=Path('app/src/main/java/com/gmailorg/hub/WakePcActivity.kt')
s=p.read_text(encoding='utf-8')
old='''            val sorted = devices.sortedWith(compareBy(
                { it.name.lowercase(java.util.Locale.ROOT) },
                { it.personName.lowercase(java.util.Locale.ROOT) },
                { it.id }
            ))
            val labels = listOf("Kies een apparaat…") + sorted.map { device ->
                val name = device.name.ifBlank { "Apparaat" }
                val person = device.personName.trim()
                (if (device.online) "● " else "○ ") + name +
                    (if (person.isNotEmpty() && person != name) " — $person" else "")
            }'''
new='''            val groups = FamilyApplicantGroups.groupDevices(devices)
            val labels = listOf("Kies een persoon of apparaat…") + groups.map { group ->
                (if (group.devices.any { it.online }) "● " else "○ ") + group.label
            }'''
assert s.count(old)==1, "Could not find original dropdown"
s=s.replace(old,new)
old='''                    if (position > 0) {
                        renderManagedDevice(details, sorted[position - 1])
                    } else {'''
new='''                    if (position > 0) {
                        val selected = groups[position - 1]
                        if (selected.family) {
                            details.addView(TextView(this@WakePcActivity).apply {
                                text = selected.devices.first().personName.trim() +
                                    " — toegang per app beheren"
                                textSize = 17f
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                setPadding(8, 8, 8, 14)
                            })
                        }
                        selected.devices.forEach { renderManagedDevice(details, it) }
                    } else {'''
assert s.count(old)==1, "Missing device-selection anchor"
s=s.replace(old,new)
old='''        val personLabel = device.personName.ifBlank { "Naam nog niet ingevuld" }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14) }
        card.addView(TextView(this).apply {
            text = (if (device.online) "●  " else "○  ") + personLabel + (if (device.owner) "  •  The One" else "")'''
new='''        val personLabel = device.personName.ifBlank { "Naam nog niet ingevuld" }
        val familyApp = when (device.familyPackage) {
            "com.theone.cinema", "com.theone.cinema.tv" -> "Cinema"
            "com.theone.series", "com.theone.series.tv" -> "Series"
            "com.theone.tv.phone", "com.theone.tv" -> "TV"
            else -> ""
        }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14) }
        card.addView(TextView(this).apply {
            text = (if (device.online) "●  " else "○  ") +
                if (familyApp.isNotEmpty()) familyApp else
                    personLabel + (if (device.owner) "  •  The One" else "")'''
assert s.count(old)==1, "Cannot find family card header"
s=s.replace(old,new)
# Keep one set of rights controls per logical app registration and ensure that
# a Cinema installation never shows misleading Series/TV approval buttons.
start=s.index('            addAccessControl(\n                "Media Player",')
end=s.index('\n            card.addView(android.widget.Button(this).apply {',start)
oldCalls=s[start:end]
assert oldCalls.count('            addAccessControl(')==11,oldCalls.count('            addAccessControl(')
family='''            if (familyApp.isNotEmpty()) {
                when (device.familyPackage) {
                    "com.theone.cinema", "com.theone.cinema.tv" -> addAccessControl(
                        "Cinema", MainDeviceRegistry.ACCESS_CINEMA,
                        device.cinemaRights, device.pendingCinema
                    )
                    "com.theone.series", "com.theone.series.tv" -> addAccessControl(
                        "Series", MainDeviceRegistry.ACCESS_SERIES,
                        device.seriesRights, device.pendingSeries
                    )
                    "com.theone.tv.phone", "com.theone.tv" -> addAccessControl(
                        "TV", MainDeviceRegistry.ACCESS_TV,
                        device.tvRights, device.pendingTv
                    )
                }
            } else {
'''+oldCalls+'''
            }
'''
s=s[:start]+family+s[end:]
assert 'selected.devices.forEach { renderManagedDevice(details, it) }' in s
assert 'requestFamilyOwnerAuthorization(scope, label)' in s
assert 'requestFamilyOwnerAuthorization(scope, "$label weigeren")' in s
assert s.count('MainDeviceRegistry.setAccessRight(')==2
assert s.count('MainDeviceRegistry.setBlocked(')==1
p.write_text(s,encoding='utf-8')
print('Main: one person dropdown, all app-specific rights preserved')
