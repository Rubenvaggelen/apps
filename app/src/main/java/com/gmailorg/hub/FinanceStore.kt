package com.gmailorg.hub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Lokale budgetadministratie voor The One. Dit is bewust geen banksaldo:
 * de gebruiker kiest zelf een startbudget en The One houdt dat bedrag bij.
 */
object FinanceStore {
    private const val PREFS = "finance_budget"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_START_CENTS = "start_cents"
    private const val KEY_BALANCE_CENTS = "balance_cents"
    private const val KEY_WARNING_CENTS = "warning_cents"
    private const val KEY_TRACKING_SINCE = "tracking_since"
    private const val KEY_TRANSACTIONS = "transactions"
    private const val KEY_PROCESSED = "processed_notification_ids"

    enum class Kind { EXPENSE, CREDIT }

    data class Transaction(
        val id: String,
        val source: String,
        val description: String,
        val amountCents: Long,
        val timestamp: Long,
        val kind: Kind,
        val automatic: Boolean
    )

    data class ApplyResult(
        val oldBalanceCents: Long,
        val newBalanceCents: Long,
        val warningCents: Long,
        val crossedWarning: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConfigured(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIGURED, false)

    fun startBudgetCents(context: Context): Long = prefs(context).getLong(KEY_START_CENTS, 0L)

    fun balanceCents(context: Context): Long = prefs(context).getLong(KEY_BALANCE_CENTS, 0L)

    fun warningCents(context: Context): Long = prefs(context).getLong(KEY_WARNING_CENTS, 0L)

    fun trackingSince(context: Context): Long = prefs(context).getLong(KEY_TRACKING_SINCE, Long.MAX_VALUE)

    fun setBudget(context: Context, startCents: Long, warningCents: Long) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putLong(KEY_START_CENTS, startCents.coerceAtLeast(0L))
            .putLong(KEY_BALANCE_CENTS, startCents.coerceAtLeast(0L))
            .putLong(KEY_WARNING_CENTS, warningCents.coerceAtLeast(0L))
            .putLong(KEY_TRACKING_SINCE, now)
            .putString(KEY_TRANSACTIONS, "[]")
            .putString(KEY_PROCESSED, "[]")
            .apply()
    }

    fun clearBudget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun addManualExpense(context: Context, amountCents: Long, description: String): ApplyResult? {
        if (amountCents <= 0L || !isConfigured(context)) return null
        return applyTransaction(
            context = context,
            transaction = Transaction(
                id = UUID.randomUUID().toString(),
                source = "Handmatig",
                description = description.ifBlank { "Handmatige uitgave" },
                amountCents = amountCents,
                timestamp = System.currentTimeMillis(),
                kind = Kind.EXPENSE,
                automatic = false
            )
        )
    }

    fun addFunds(context: Context, amountCents: Long, description: String = "Bedrag toegevoegd"): ApplyResult? {
        if (amountCents <= 0L || !isConfigured(context)) return null
        return applyTransaction(
            context = context,
            transaction = Transaction(
                id = UUID.randomUUID().toString(),
                source = "Handmatig",
                description = description,
                amountCents = amountCents,
                timestamp = System.currentTimeMillis(),
                kind = Kind.CREDIT,
                automatic = false
            )
        )
    }

    /**
     * Verwerkt een automatische betaalmelding precies één keer.
     */
    fun addAutomaticExpense(
        context: Context,
        uniqueNotificationId: String,
        source: String,
        description: String,
        amountCents: Long,
        timestamp: Long
    ): ApplyResult? {
        if (!isConfigured(context) || amountCents <= 0L) return null
        if (timestamp < trackingSince(context)) return null
        if (isProcessed(context, uniqueNotificationId)) return null

        val result = applyTransaction(
            context = context,
            transaction = Transaction(
                id = uniqueNotificationId,
                source = source,
                description = description.ifBlank { source },
                amountCents = amountCents,
                timestamp = timestamp,
                kind = Kind.EXPENSE,
                automatic = true
            )
        )
        if (result != null) rememberProcessed(context, uniqueNotificationId)
        return result
    }

    private fun applyTransaction(context: Context, transaction: Transaction): ApplyResult {
        val p = prefs(context)
        val oldBalance = p.getLong(KEY_BALANCE_CENTS, 0L)
        val newBalance = when (transaction.kind) {
            Kind.EXPENSE -> oldBalance - transaction.amountCents
            Kind.CREDIT -> oldBalance + transaction.amountCents
        }
        val warning = p.getLong(KEY_WARNING_CENTS, 0L)
        val crossed = warning > 0L && oldBalance > warning && newBalance <= warning

        val transactions = getTransactions(context).toMutableList()
        transactions.add(0, transaction)
        while (transactions.size > 250) transactions.removeAt(transactions.lastIndex)

        p.edit()
            .putLong(KEY_BALANCE_CENTS, newBalance)
            .putString(KEY_TRANSACTIONS, encodeTransactions(transactions))
            .apply()

        return ApplyResult(oldBalance, newBalance, warning, crossed)
    }

    fun undoTransaction(context: Context, transactionId: String): Boolean {
        if (!isConfigured(context)) return false
        val list = getTransactions(context).toMutableList()
        val index = list.indexOfFirst { it.id == transactionId }
        if (index < 0) return false
        val tx = list.removeAt(index)
        val current = balanceCents(context)
        val restored = when (tx.kind) {
            Kind.EXPENSE -> current + tx.amountCents
            Kind.CREDIT -> current - tx.amountCents
        }
        prefs(context).edit()
            .putLong(KEY_BALANCE_CENTS, restored)
            .putString(KEY_TRANSACTIONS, encodeTransactions(list))
            .apply()
        return true
    }

    fun getTransactions(context: Context): List<Transaction> {
        val raw = prefs(context).getString(KEY_TRANSACTIONS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        Transaction(
                            id = o.optString("id"),
                            source = o.optString("source"),
                            description = o.optString("description"),
                            amountCents = o.optLong("amountCents"),
                            timestamp = o.optLong("timestamp"),
                            kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.EXPENSE),
                            automatic = o.optBoolean("automatic", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun totalSpentCents(context: Context): Long =
        getTransactions(context).filter { it.kind == Kind.EXPENSE }.sumOf { it.amountCents }

    private fun encodeTransactions(items: List<Transaction>): String {
        val array = JSONArray()
        items.forEach { tx ->
            array.put(
                JSONObject()
                    .put("id", tx.id)
                    .put("source", tx.source)
                    .put("description", tx.description)
                    .put("amountCents", tx.amountCents)
                    .put("timestamp", tx.timestamp)
                    .put("kind", tx.kind.name)
                    .put("automatic", tx.automatic)
            )
        }
        return array.toString()
    }

    private fun isProcessed(context: Context, id: String): Boolean {
        val raw = prefs(context).getString(KEY_PROCESSED, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).any { array.optString(it) == id }
        } catch (_: Exception) {
            false
        }
    }

    private fun rememberProcessed(context: Context, id: String) {
        val p = prefs(context)
        val existing = mutableListOf<String>()
        try {
            val array = JSONArray(p.getString(KEY_PROCESSED, "[]") ?: "[]")
            for (i in 0 until array.length()) existing.add(array.optString(i))
        } catch (_: Exception) {}
        if (!existing.contains(id)) existing.add(0, id)
        while (existing.size > 300) existing.removeAt(existing.lastIndex)
        val out = JSONArray()
        existing.forEach { out.put(it) }
        p.edit().putString(KEY_PROCESSED, out.toString()).apply()
    }
}
