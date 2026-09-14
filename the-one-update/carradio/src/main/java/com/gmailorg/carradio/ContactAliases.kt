package com.gmailorg.carradio

/**
 * Vaste WhatsApp-contacten in The One Car.
 * realName moet overeenkomen met de naam van het WhatsApp-gesprek op de telefoon.
 */
object ContactAliases {
    data class FixedContact(
        val realName: String,
        val displayName: String,
        val phoneNumber: String? = null
    )

    val fixedContacts = listOf(
        FixedContact("Beyonce aka FARQUAAD Van Aggelen", "Dochter"),
        FixedContact("Susan Oehlers", "Ma"),
        FixedContact("SST", "CA"),
        FixedContact("Ruben Werk Tel", "Test"),
        FixedContact("Devon", "Devon", "+31627552130"),
        FixedContact("Envy", "Envy", "+31643289810")
    )

    val defaultRealNames: Set<String> get() = fixedContacts.mapTo(linkedSetOf()) { it.realName }

    fun isFixed(realName: String): Boolean =
        fixedContacts.any { it.realName.equals(realName.trim(), ignoreCase = true) }

    fun canonicalRealName(realName: String): String? {
        val clean = realName.trim()
        return fixedContacts.firstOrNull { it.realName.equals(clean, ignoreCase = true) }?.realName
    }

    fun displayName(realName: String): String =
        fixedContacts.firstOrNull { it.realName.equals(realName.trim(), ignoreCase = true) }?.displayName
            ?: realName.trim()

    fun phoneNumber(realName: String): String? =
        fixedContacts.firstOrNull { it.realName.equals(realName.trim(), ignoreCase = true) }?.phoneNumber

    /**
     * Gebruiksvriendelijke invoer/compatibiliteit met oudere builds.
     */
    fun resolveRealName(input: String): String {
        val clean = input.trim()
        return fixedContacts.firstOrNull {
            it.displayName.equals(clean, ignoreCase = true) ||
                it.phoneNumber?.equals(clean, ignoreCase = true) == true ||
                it.realName.equals(clean, ignoreCase = true)
        }?.realName ?: clean
    }
}
