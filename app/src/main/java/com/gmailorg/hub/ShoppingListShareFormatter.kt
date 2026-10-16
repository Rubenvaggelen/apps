package com.gmailorg.hub

/**
 * Read-only snapshot for sharing. Never changes a shopping item or its status.
 * WhatsApp applies *bold* to the headings.
 */
internal object ShoppingListShareFormatter {
    fun format(items: List<ShoppingItem>): String? {
        val pending = mutableListOf<String>()
        val completed = mutableListOf<String>()
        for (item in items) {
            val description = item.text.trim().replace(Regex("\\s+"), " ")
            if (description.isBlank()) continue
            if (item.done) completed.add(description) else pending.add(description)
        }
        if (pending.isEmpty() && completed.isEmpty()) return null
        return buildString {
            append("*Boodschappenlijst* 🛒")
            if (pending.isNotEmpty()) {
                append("\n\n*Nog halen:*")
                pending.forEach { append("\n☐ ").append(it) }
            }
            if (completed.isNotEmpty()) {
                append("\n\n*Afgevinkt:*")
                completed.forEach { append("\n☑ ").append(it) }
            }
        }
    }
}
