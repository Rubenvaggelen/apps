package com.gmailorg.carradio

/**
 * Alleen presentatie: het echte WhatsApp-gesprek blijft altijd de oorspronkelijke naam gebruiken,
 * zodat antwoorden naar het juiste gesprek op de telefoon gaan.
 */
object ContactAliases {
    private val aliases = linkedMapOf(
        "Beyonce aka FARQUAAD Van Aggelen" to "Dochter",
        "Susan Oehlers" to "Ma",
        "SST" to "CA",
        "Ruben Werk Tel" to "Test"
    )

    val defaultRealNames: Set<String> get() = aliases.keys

    fun displayName(realName: String): String =
        aliases.entries.firstOrNull { it.key.equals(realName.trim(), ignoreCase = true) }?.value
            ?: realName.trim()

    /**
     * Maakt invoer op de radio gebruiksvriendelijk: "Dochter", "Ma", "CA" en "Test"
     * worden automatisch terugvertaald naar de echte WhatsApp-naam.
     */
    fun resolveRealName(input: String): String {
        val clean = input.trim()
        return aliases.entries.firstOrNull { it.value.equals(clean, ignoreCase = true) }?.key ?: clean
    }
}
