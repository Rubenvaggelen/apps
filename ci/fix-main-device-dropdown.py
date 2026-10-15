#!/usr/bin/env python3
"""Render only one selected device in Main's management dialog."""
from pathlib import Path

file = Path("app/src/main/java/com/gmailorg/hub/WakePcActivity.kt")
content = file.read_text(encoding="utf-8")
start = content.index("    private fun showManagedDevices(devices: List<MainRegisteredDevice>) {")
end = content.index("    private fun managedDeviceStateText(", start)
original = content[start:end]
loop_start = "        devices.forEach { device ->\n"
i = original.index(loop_start) + len(loop_start)
j = original.index("        val scroll = android.widget.ScrollView(this)", i)
inner_lines = original[i:j].splitlines(keepends=True)
assert inner_lines[-1].strip() == "}", inner_lines[-3:]
inner_lines = inner_lines[:-1]
card_code = "".join(line[4:] if line.startswith("    ") else line for line in inner_lines)
assert "MainDeviceRegistry.setAccessRight" in card_code
assert "requestFamilyOwnerAuthorization(scope, label)" in card_code
assert 'requestFamilyOwnerAuthorization(scope, "$label weigeren")' in card_code
assert "MainDeviceRegistry.setBlocked" in card_code

replacement = """    private fun showManagedDevices(devices: List<MainRegisteredDevice>) {
        // Select a device first. Only that device's rights are rendered.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 6)
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }
        root.addView(TextView(this).apply {
            text = "Selecteer een apparaat"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_main))
        })

        if (devices.isEmpty()) {
            details.addView(TextView(this).apply {
                text = "Nog geen apparaten geregistreerd."
                textSize = 14f
            })
        } else {
            val sorted = devices.sortedWith(compareBy(
                { it.name.lowercase(java.util.Locale.ROOT) },
                { it.personName.lowercase(java.util.Locale.ROOT) },
                { it.id }
            ))
            val labels = listOf("Kies een apparaat…") + sorted.map { device ->
                val name = device.name.ifBlank { "Apparaat" }
                val person = device.personName.trim()
                (if (device.online) "● " else "○ ") + name +
                    (if (person.isNotEmpty() && person != name) " — $person" else "")
            }
            val selector = android.widget.Spinner(this)
            val adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_spinner_item, labels
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            selector.adapter = adapter
            selector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    details.removeAllViews()
                    if (position > 0) {
                        renderManagedDevice(details, sorted[position - 1])
                    } else {
                        details.addView(TextView(this@WakePcActivity).apply {
                            text = "Kies hierboven een apparaat om de rechten te beheren."
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(this@WakePcActivity, R.color.text_dim))
                            setPadding(8, 24, 8, 8)
                        })
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                    details.removeAllViews()
                }
            }
            root.addView(selector, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(details)
        }
        val maxHeight = (resources.displayMetrics.heightPixels * 0.54f).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, maxHeight
        ))
        AlertDialog.Builder(this)
            .setTitle("Apparaten beheren")
            .setView(root)
            .setNegativeButton("Sluiten", null)
            .show()
    }

    private fun renderManagedDevice(list: LinearLayout, device: MainRegisteredDevice) {
""" + card_code + """    }

"""
assert content.count("    private fun showManagedDevices(") == 1
patched = content[:start] + replacement + content[end:]
assert patched.count("MainDeviceRegistry.setAccessRight(") == content.count("MainDeviceRegistry.setAccessRight(")
assert patched.count("MainDeviceRegistry.setBlocked(") == content.count("MainDeviceRegistry.setBlocked(")
assert patched.count("requestFamilyOwnerAuthorization(") == content.count("requestFamilyOwnerAuthorization(")
assert "devices.forEach {" not in patched[start:patched.index("private fun managedDeviceStateText(", start)]
assert "renderManagedDevice(details, sorted[position - 1])" in patched
file.write_text(patched, encoding="utf-8")
print("One-device dropdown applied. Rights, owner PIN and blocking handlers preserved.")
