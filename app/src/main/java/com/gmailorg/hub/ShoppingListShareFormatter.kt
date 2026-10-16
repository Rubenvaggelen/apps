package com.gmailorg.hub

/** Formats a read-only shopping-list snapshot for WhatsApp and Android's share sheet. */
object ShoppingListShareFormatter {
    fun format(items: List<ShoppingItem>): String {
        if (items.isEmpty()) return ""

        fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()
        val pending = items.filterNot { it.done }.map { clean(it.text) }.filter { it.isNotEmpty() }
        val completed = items.filter { it.done }.map { clean(it.text) }.filter { it.isNotEmpty() }
        if (pending.isEmpty() && completed.isEmpty()) return ""

        return buildString {
            append("🛒 Boodschappenlijst - The One")
            if (pending.isNotEmpty()) {
                append("\n\nNog kopen:")
                pending.forEach { append("\n☐ ").append(it) }
            }
            if (completed.isNotEmpty()) {
                append("\n\nAfgevinkt:")
                completed.forEach { append("\n✓ ").append(it) }
            }
        }
    }
}
