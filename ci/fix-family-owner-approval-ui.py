#!/usr/bin/env python3
"""Small in-repo UI fix. Changes only explicit license buttons, preserves existing system config."""
from pathlib import Path

p=Path('app/src/main/java/com/gmailorg/hub/WakePcActivity.kt')
s=p.read_text(encoding='utf-8')
assert 'requestFamilyOwnerAuthorization' not in s

anchor='    private var activeDeviceAdminPin = ""'
assert s.count(anchor)==1
helper='''
    /**
     * Family approvals require the existing owner credential. Ask only on
     * Family grant/deny; never persist the credential in preferences or logs.
     */
    private fun requestFamilyOwnerAuthorization(
        scope: String,
        label: String,
        proceed: (String) -> Unit
    ) {
        if (scope !in setOf(
                MainDeviceRegistry.ACCESS_CINEMA,
                MainDeviceRegistry.ACCESS_SERIES,
                MainDeviceRegistry.ACCESS_TV
            )) {
            proceed(activeDeviceAdminPin)
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Eigenaar-PIN"
            isSingleLine = true
            setPadding(28, 14, 28, 14)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("$label: eigenaarstoestemming")
            .setMessage("Voer de bestaande beheer-PIN in om de app-toegang te wijzigen. De PIN wordt niet opgeslagen.")
            .setView(input)
            .setNegativeButton("Annuleren", null)
            .setPositiveButton("Bevestigen", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.isEmpty()) {
                    input.error = "Voer de beheer-PIN in"
                } else {
                    dialog.dismiss()
                    proceed(pin)
                }
            }
        }
        dialog.show()
    }
'''
s=s.replace(anchor, anchor+"\n"+helper, 1)

start='''                        setOnClickListener {
                            isEnabled = false
                            val enable = !allowed
                            Thread {
                                val result = runCatching {
                                    MainDeviceRegistry.setAccessRight(
                                        this@WakePcActivity,
                                        activeDeviceAdminPin,''';
replacement='''                        setOnClickListener {
                            val enable = !allowed
                            requestFamilyOwnerAuthorization(scope, label) { pin ->
                            isEnabled = false
                            Thread {
                                val result = runCatching {
                                    MainDeviceRegistry.setAccessRight(
                                        this@WakePcActivity,
                                        pin,''';
assert s.count(start)==1
s=s.replace(start,replacement,1)

end='''                                }
                            }.start()
                        }
                    }
                    buttons.addView('''
end_new='''                                }
                            }.start()
                            }
                        }
                    }
                    buttons.addView('''
assert s.count(end)==1
s=s.replace(end,end_new,1)

denial='''                                setOnClickListener {
                                    isEnabled = false
                                    Thread {
                                        val result = runCatching {
                                            MainDeviceRegistry.setAccessRight(
                                                this@WakePcActivity,
                                                activeDeviceAdminPin,''';
denial_new='''                                setOnClickListener {
                                    requestFamilyOwnerAuthorization(scope, "$label weigeren") { pin ->
                                    isEnabled = false
                                    Thread {
                                        val result = runCatching {
                                            MainDeviceRegistry.setAccessRight(
                                                this@WakePcActivity,
                                                pin,''';
assert s.count(denial)==1
s=s.replace(denial,denial_new,1)

deny_end='''                                        }
                                    }.start()
                                }
                            },
                            LinearLayout.LayoutParams('''
deny_end_new='''                                        }
                                    }.start()
                                    }
                                }
                            },
                            LinearLayout.LayoutParams('''
assert s.count(deny_end)==1
s=s.replace(deny_end,deny_end_new,1)

assert s.count('requestFamilyOwnerAuthorization(scope,')==2
assert s.count('pin,\n                                        device.id')==1
assert s.count('pin,\n                                                device.id')==1
assert 'MainDeviceRegistry.setAccessRight(' in s
p.write_text(s,encoding='utf-8')
print('Family PIN approval dialog patched; two actions, no persisted credential.')
