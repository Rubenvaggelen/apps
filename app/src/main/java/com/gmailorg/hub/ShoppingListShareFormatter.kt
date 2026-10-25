package com.gmailorg.hub

/**
 * Builds a WhatsApp-friendly copy of just the outstanding groceries.
 * Never mutate the original list or mark items as purchased when sharing.
 */
object ShoppingListShareFormatter {
    fun format(items: List<ShoppingItem>): String? {
        val pending = items.asSequence()
            .filterNot { it.done }
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (pending.isEmpty()) return null
        return buildString {
            append("🛒 Boodschappenlijst\n\n")
            pending.forEach { item ->
                append("☐ ").append(item).append('\n')
            }
        }.trimEnd()
    }
}
