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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Verwerkt betaalmeldingen van Google Wallet/Pay, Tikkie en ING.
 *
 * Belangrijk: bank-/betaalapps stoppen het bedrag niet altijd in EXTRA_TEXT.
 * Daarom krijgt deze processor alle tekst uit de notification extras aangeleverd
 * en accepteert hij voor bekende betaalapps ook bedragen als "0,50" zonder €-teken.
 */
object FinanceNotificationProcessor {
    private const val CHANNEL_ID = "finance_budget_alerts"
    private const val ALERT_ID = 7401
    private const val DEBUG_PREFS = "finance_detection_debug"
    private const val KEY_LAST_DIAGNOSTIC = "last_diagnostic"

    private val explicitMoneyPatterns = listOf(
        Regex("(?:€|EUR|euro)\\s*([0-9]{1,6}(?:[.,][0-9]{1,2})?)", RegexOption.IGNORE_CASE),
        Regex("([0-9]{1,6}(?:[.,][0-9]{1,2})?)\\s*(?:€|EUR|euro)", RegexOption.IGNORE_CASE),
        Regex("[-−–]\\s*(?:€|EUR)?\\s*([0-9]{1,6}(?:[.,][0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    )

    // Fallback voor meldingen zoals "Betaling 0,50 gelukt" waarin geen valuta staat.
    // Alleen gebruikt nadat we al zeker weten dat de melding van Wallet/Tikkie/ING komt.
    private val bareDecimalPattern = Regex("(?<![0-9])([0-9]{1,6}[.,][0-9]{2})(?![0-9])")

    fun process(
        context: Context,
        packageName: String,
        appLabel: String,
        title: String,
        body: String,
        notificationKey: String,
        postTime: Long
    ) {
        val source = sourceFor(packageName, appLabel) ?: return
        val combined = "$title\n$body"
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        if (combined.isBlank()) {
            setDiagnostic(context, source, "melding gezien, maar zonder leesbare tekst", postTime)
            return
        }

        if (!FinanceStore.isConfigured(context)) {
            setDiagnostic(context, source, "melding gezien; stel eerst een budget in", postTime)
            return
        }

        val lower = combined.lowercase(Locale.ROOT)
        val amountCents = parseEuroCents(source, combined)
        if (amountCents == null) {
            setDiagnostic(context, source, "melding gezien, maar geen bedrag gevonden", postTime)
            return
        }

        if (!isOutgoingPayment(source, lower)) {
            setDiagnostic(context, source, "${formatMoney(amountCents)} gezien, maar dit lijkt geen uitgaande betaling", postTime)
            return
        }

        val description = extractDescription(source, title, body)
        val fingerprint = stableId(packageName, notificationKey, amountCents, postTime)
        val result = FinanceStore.addAutomaticExpense(
            context = context,
            uniqueNotificationId = fingerprint,
            source = source,
            description = description,
            amountCents = amountCents,
            timestamp = postTime
        )

        if (result == null) {
            setDiagnostic(context, source, "${formatMoney(amountCents)} gezien; niet nogmaals afgetrokken (dubbel of oude melding)", postTime)
            return
        }

        setDiagnostic(context, source, "${formatMoney(amountCents)} automatisch afgetrokken", postTime)
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
            pkg == "com.abnamro.nl.tikkie" || pkg == "com.abnamro.nl.tikkie.business" ||
                pkg.contains("tikkie") || label.contains("tikkie") -> "Tikkie"
            pkg == "com.ing.mobile" || label == "ing" || label.startsWith("ing ") || label.contains("ing bank") || label.contains("ing nederland") -> "ING"
            else -> null
        }
    }

    private fun isOutgoingPayment(source: String, lower: String): Boolean {
        // Ontvangsten/refunds mogen nooit van het budget af.
        val incomingOrRefund = listOf(
            "terugbetaling", "terugbetaald", "refund", "refunded",
            "ontvangen", "bijgeschreven", "geld ontvangen", "creditering",
            "heeft je tikkie betaald", "heeft jouw tikkie betaald",
            "je tikkie is betaald", "jouw tikkie is betaald",
            "je betaalverzoek is betaald", "jouw betaalverzoek is betaald",
            "betaalverzoek ontvangen", "is naar je overgemaakt", "aan jou betaald",
            "bijschrijving", "geld op je rekening"
        ).any { lower.contains(it) }
        if (incomingOrRefund) return false

        // Een aangemaakt/verstuurd betaalverzoek is nog geen uitgave.
        val requestOnly = listOf(
            "betaalverzoek aangemaakt", "betaalverzoek verstuurd", "betaalverzoek gedeeld",
            "tikkie aangemaakt", "tikkie verstuurd", "verzoek verstuurd", "deel je tikkie",
            "betaalverzoek van jou", "nieuw betaalverzoek"
        ).any { lower.contains(it) }
        if (requestOnly) return false

        return when (source) {
            "Google Wallet" -> true
            // Bij Tikkie is een bedragdragende melding die niet over ontvangst of het
            // aanmaken van een verzoek gaat vrijwel altijd de betaling/afschrijving.
            // We accepteren daarom ook generieke bevestigingen zonder exact woord "betaald".
            "Tikkie" -> true
            "ING" -> {
                val obviousNonPayment = listOf(
                    "saldo", "spaardoel", "rente", "inloggen", "nieuw bericht",
                    "creditcardoverzicht", "rekeningoverzicht"
                ).any { lower.contains(it) }
                if (obviousNonPayment) return false

                listOf(
                    "afgeschreven", "afschrijving", "van je rekening",
                    "je hebt betaald", "jij hebt betaald", "betaald",
                    "betaling", "ideal", "i-deal", "pinbetaling", "pasbetaling",
                    "betaalpas", "kaartbetaling", "aankoop", "debet",
                    "overboeking", "transactie", "rekening verlaten"
                ).any { lower.contains(it) } || lower.contains("-") || lower.contains("−")
            }
            else -> false
        }
    }

    private fun parseEuroCents(source: String, text: String): Long? {
        val explicitRaw = explicitMoneyPatterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.getOrNull(1)
        }
        val raw = explicitRaw ?: when (source) {
            "Tikkie", "ING", "Google Wallet" -> bareDecimalPattern.find(text)?.groupValues?.getOrNull(1)
            else -> null
        } ?: return null

        val normalized = if (raw.contains(',') && raw.contains('.')) {
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
            "google wallet", "wallet", "google pay", "tikkie", "ing", "ing nederland", "betaling", "payment"
        )
        val candidate = if (titleTrim.isNotBlank() && !genericTitle) titleTrim else body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return candidate
            .replace(Regex("(?:€|EUR|euro)\\s*[0-9.,]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[0-9.,]+\\s*(?:€|EUR|euro)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '–', '•', ':')
            .take(90)
            .ifBlank { "$source betaling" }
    }

    private fun stableId(packageName: String, key: String, amountCents: Long, postTime: Long): String {
        // 30-secondenbucket voorkomt dat een update van exact dezelfde notification
        // dezelfde betaling opnieuw aftrekt, zonder een volgende betaling uren later te blokkeren.
        val bucket = postTime / 30_000L
        val input = "$packageName|$key|$amountCents|$bucket"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun setDiagnostic(context: Context, source: String, message: String, timestamp: Long) {
        val time = SimpleDateFormat("HH:mm:ss", Locale("nl", "NL")).format(Date(timestamp.coerceAtLeast(0L)))
        context.applicationContext.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DIAGNOSTIC, "$time • $source • $message")
            .apply()
    }

    fun lastDiagnostic(context: Context): String =
        context.applicationContext.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DIAGNOSTIC, null)
            ?: "Nog geen Wallet-, Tikkie- of ING-melding gezien sinds deze versie."

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
