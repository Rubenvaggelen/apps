package com.gmailorg.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale

/**
 * Haalt uitsluitend duidelijke uitgaande betalingen uit Google Wallet/Pay- en
 * Tikkie-meldingen. Ontvangsten/refunds worden bewust genegeerd.
 */
object FinanceNotificationProcessor {
    private const val CHANNEL_ID = "finance_budget_alerts"
    private const val ALERT_ID = 7401

    private val moneyPatterns = listOf(
        Regex("(?:€|EUR)\\s*([0-9]{1,6}(?:[.,][0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        Regex("([0-9]{1,6}(?:[.,][0-9]{1,2})?)\\s*(?:€|EUR)", RegexOption.IGNORE_CASE)
    )

    fun process(
        context: Context,
        packageName: String,
        appLabel: String,
        title: String,
        body: String,
        notificationKey: String,
        postTime: Long
    ) {
        if (!FinanceStore.isConfigured(context)) return

        val source = sourceFor(packageName, appLabel) ?: return
        val combined = "$title\n$body".replace('\u00A0', ' ').trim()
        if (combined.isBlank()) return

        val lower = combined.lowercase(Locale.ROOT)
        val amountCents = parseEuroCents(combined) ?: return
        if (!isOutgoingPayment(source, lower)) return

        val description = extractDescription(source, title, body)
        val fingerprint = stableId(packageName, notificationKey, amountCents, postTime)
        val result = FinanceStore.addAutomaticExpense(
            context = context,
            uniqueNotificationId = fingerprint,
            source = source,
            description = description,
            amountCents = amountCents,
            timestamp = postTime
        ) ?: return

        if (result.crossedWarning) {
            showThresholdAlert(context, result.newBalanceCents, result.warningCents)
        }
    }

    private fun sourceFor(packageName: String, appLabel: String): String? {
        val pkg = packageName.lowercase(Locale.ROOT)
        val label = appLabel.lowercase(Locale.ROOT)
        return when {
            pkg == "com.google.android.apps.walletnfcrel" ||
                pkg.contains("wallet") || label.contains("google wallet") || label == "wallet" || label.contains("google pay") ->
                "Google Wallet"
            pkg.contains("tikkie") || label.contains("tikkie") -> "Tikkie"
            else -> null
        }
    }

    private fun isOutgoingPayment(source: String, lower: String): Boolean {
        // Nooit een terugbetaling/ontvangst van het budget aftrekken.
        val incomingOrRefund = listOf(
            "terugbetaling", "terugbetaald", "refund", "refunded",
            "ontvangen", "bijgeschreven", "geld ontvangen",
            "heeft je tikkie betaald", "heeft jouw tikkie betaald",
            "is naar je overgemaakt", "aan jou betaald"
        ).any { lower.contains(it) }
        if (incomingOrRefund) return false

        return when (source) {
            // Een Google Wallet/Google Pay-notificatie met een EUR-bedrag is in
            // de praktijk een transactiemelding. Refunds/ontvangsten zijn hierboven
            // al uitgesloten, dus vereis hier geen specifiek woord als "betaald".
            "Google Wallet" -> true
            "Tikkie" -> listOf(
                "je hebt betaald", "jij hebt betaald", "betaling gelukt",
                "betaling voltooid", "afgeschreven", "betaald via tikkie",
                "je betaling is gelukt", "tikkie betaling"
            ).any { lower.contains(it) }
            else -> false
        }
    }

    private fun parseEuroCents(text: String): Long? {
        val raw = moneyPatterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.getOrNull(1)
        } ?: return null
        val normalized = if (raw.contains(',') && raw.contains('.')) {
            // Europese notatie zoals 1.234,56
            raw.replace(".", "").replace(',', '.')
        } else {
            raw.replace(',', '.')
        }
        val value = normalized.toDoubleOrNull() ?: return null
        if (value <= 0.0 || value > 100_000.0) return null
        return kotlin.math.round(value * 100.0).toLong()
    }

    private fun extractDescription(source: String, title: String, body: String): String {
        val titleTrim = title.trim()
        val genericTitle = titleTrim.lowercase(Locale.ROOT) in setOf(
            "google wallet", "wallet", "google pay", "tikkie", "betaling", "payment"
        )
        val candidate = if (titleTrim.isNotBlank() && !genericTitle) titleTrim else body.trim()
        return candidate
            .replace(Regex("(?:€|EUR)\\s*[0-9.,]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[0-9.,]+\\s*(?:€|EUR)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '–', '•', ':')
            .take(90)
            .ifBlank { "$source betaling" }
    }

    private fun stableId(packageName: String, key: String, amountCents: Long, postTime: Long): String {
        // postTime blijft bij een update van dezelfde Android-notificatie gelijk,
        // maar verandert bij een nieuwe betaling. Zo telt een bijgewerkte melding
        // niet dubbel en kan dezelfde notification-id later toch opnieuw worden gebruikt.
        val input = "$packageName|$key|$amountCents|$postTime"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun showThresholdAlert(context: Context, balanceCents: Long, warningCents: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Financiën waarschuwingen",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Waarschuwt wanneer je ingestelde The One-budgetgrens is bereikt."
                }
            )
        }

        val openIntent = Intent(context, FinanceActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            7401,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val balance = formatMoney(balanceCents)
        val warning = formatMoney(warningCents)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Financiën – budgetgrens bereikt")
            .setContentText("Resterend: $balance. Je waarschuwing staat op $warning.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Je beschikbare The One-budget is gedaald naar $balance. Je ingestelde waarschuwing staat op $warning."
                )
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(ALERT_ID, notification)
    }

    fun showManualThresholdIfNeeded(context: Context, result: FinanceStore.ApplyResult?) {
        if (result?.crossedWarning == true) {
            showThresholdAlert(context, result.newBalanceCents, result.warningCents)
        }
    }

    private fun formatMoney(cents: Long): String {
        val nf = NumberFormat.getCurrencyInstance(Locale("nl", "NL"))
        return nf.format(cents / 100.0)
    }
}
