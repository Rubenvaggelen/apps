package com.gmailorg.hub

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FinanceActivity : AppCompatActivity() {
    private lateinit var balanceText: TextView
    private lateinit var startBudgetText: TextView
    private lateinit var spentText: TextView
    private lateinit var warningText: TextView
    private lateinit var autoStatusText: TextView
    private lateinit var emptyState: TextView
    private lateinit var adapter: FinanceTransactionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finance)

        balanceText = findViewById(R.id.financeBalanceText)
        startBudgetText = findViewById(R.id.financeStartBudgetText)
        spentText = findViewById(R.id.financeSpentText)
        warningText = findViewById(R.id.financeWarningText)
        autoStatusText = findViewById(R.id.financeAutoStatusText)
        emptyState = findViewById(R.id.financeEmptyState)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.setBudgetButton).setOnClickListener { showSetBudgetDialog() }
        findViewById<View>(R.id.addFundsButton).setOnClickListener { showAmountDialog(isExpense = false) }
        findViewById<View>(R.id.manualExpenseButton).setOnClickListener { showAmountDialog(isExpense = true) }
        findViewById<View>(R.id.notificationAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val list = findViewById<RecyclerView>(R.id.financeTransactionsList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = FinanceTransactionAdapter { tx ->
            AlertDialog.Builder(this)
                .setTitle("Transactie ongedaan maken?")
                .setMessage("${tx.description} (${formatMoney(tx.amountCents)}) wordt teruggedraaid.")
                .setPositiveButton("Ongedaan maken") { _, _ ->
                    if (FinanceStore.undoTransaction(this, tx.id)) refresh()
                }
                .setNegativeButton("Annuleren", null)
                .show()
        }
        list.adapter = adapter
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val configured = FinanceStore.isConfigured(this)
        if (!configured) {
            balanceText.text = "Nog geen budget"
            startBudgetText.text = "Startbudget: —"
            spentText.text = "Uitgegeven: —"
            warningText.text = "Waarschuwing: —"
            adapter.updateItems(emptyList())
            emptyState.visibility = View.VISIBLE
        } else {
            balanceText.text = formatMoney(FinanceStore.balanceCents(this))
            startBudgetText.text = "Startbudget: ${formatMoney(FinanceStore.startBudgetCents(this))}"
            spentText.text = "Uitgegeven: ${formatMoney(FinanceStore.totalSpentCents(this))}"
            val warning = FinanceStore.warningCents(this)
            warningText.text = if (warning > 0) {
                "Waarschuwing bij: ${formatMoney(warning)}"
            } else {
                "Waarschuwing: uit"
            }
            val items = FinanceStore.getTransactions(this)
            adapter.updateItems(items)
            emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        autoStatusText.text = if (listenerEnabled) {
            "Automatisch actief: Google Wallet / Google Pay + Tikkie + ING. Ontvangsten en terugbetalingen worden niet afgetrokken."
        } else {
            "Automatisch verwerken staat nog uit. Geef The One notificatietoegang voor Google Wallet / Google Pay, Tikkie en ING."
        }
        autoStatusText.setTextColor(
            ContextCompat.getColor(this, if (listenerEnabled) R.color.sage else R.color.amber)
        )
    }

    private fun showSetBudgetDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_finance_budget, null)
        val budgetInput = view.findViewById<EditText>(R.id.budgetAmountInput)
        val warningInput = view.findViewById<EditText>(R.id.warningAmountInput)

        if (FinanceStore.isConfigured(this)) {
            budgetInput.setText(centsToInput(FinanceStore.startBudgetCents(this)))
            val warning = FinanceStore.warningCents(this)
            if (warning > 0) warningInput.setText(centsToInput(warning))
        }

        AlertDialog.Builder(this)
            .setTitle("Nieuw budget instellen")
            .setMessage("Je huidige transactielijst wordt leeggemaakt. Nieuwe Wallet-, Tikkie- en ING-betalingen worden vanaf dit moment bijgehouden.")
            .setView(view)
            .setPositiveButton("Opslaan", null)
            .setNegativeButton("Annuleren", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val start = parseMoneyToCents(budgetInput.text.toString())
                        val warning = parseMoneyToCents(warningInput.text.toString()) ?: 0L
                        if (start == null || start <= 0L) {
                            Toast.makeText(this, "Vul een geldig startbedrag in.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (warning < 0L) {
                            Toast.makeText(this, "Vul een geldige waarschuwing in.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        FinanceStore.setBudget(this, start, warning)
                        dialog.dismiss()
                        refresh()
                    }
                }
                dialog.show()
            }
    }

    private fun showAmountDialog(isExpense: Boolean) {
        if (!FinanceStore.isConfigured(this)) {
            Toast.makeText(this, "Stel eerst een budget in.", Toast.LENGTH_SHORT).show()
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_finance_amount, null)
        val amount = view.findViewById<EditText>(R.id.financeAmountInput)
        val description = view.findViewById<EditText>(R.id.financeDescriptionInput)
        AlertDialog.Builder(this)
            .setTitle(if (isExpense) "Handmatige uitgave" else "Bedrag toevoegen")
            .setView(view)
            .setPositiveButton(if (isExpense) "Aftrekken" else "Toevoegen", null)
            .setNegativeButton("Annuleren", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val cents = parseMoneyToCents(amount.text.toString())
                        if (cents == null || cents <= 0L) {
                            Toast.makeText(this, "Vul een geldig bedrag in.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val result = if (isExpense) {
                            FinanceStore.addManualExpense(this, cents, description.text.toString().trim())
                        } else {
                            FinanceStore.addFunds(this, cents, description.text.toString().trim().ifBlank { "Bedrag toegevoegd" })
                        }
                        FinanceNotificationProcessor.showManualThresholdIfNeeded(this, result)
                        dialog.dismiss()
                        refresh()
                    }
                }
                dialog.show()
            }
    }

    private fun parseMoneyToCents(raw: String): Long? {
        val cleaned = raw.trim().replace("€", "").replace(" ", "")
        if (cleaned.isBlank()) return null
        val normalized = if (cleaned.contains(',') && cleaned.contains('.')) {
            cleaned.replace(".", "").replace(',', '.')
        } else {
            cleaned.replace(',', '.')
        }
        val value = normalized.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0.0 || value > 10_000_000.0) return null
        return kotlin.math.round(value * 100.0).toLong()
    }

    private fun centsToInput(cents: Long): String = String.format(Locale.US, "%.2f", cents / 100.0).replace('.', ',')

    private fun formatMoney(cents: Long): String =
        NumberFormat.getCurrencyInstance(Locale("nl", "NL")).format(cents / 100.0)
}

class FinanceTransactionAdapter(
    private val onUndo: (FinanceStore.Transaction) -> Unit
) : RecyclerView.Adapter<FinanceTransactionAdapter.ViewHolder>() {
    private var items = listOf<FinanceStore.Transaction>()
    private val formatter = SimpleDateFormat("dd-MM HH:mm", Locale("nl", "NL"))

    fun updateItems(newItems: List<FinanceStore.Transaction>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val description: TextView = view.findViewById(R.id.financeTransactionDescription)
        val meta: TextView = view.findViewById(R.id.financeTransactionMeta)
        val amount: TextView = view.findViewById(R.id.financeTransactionAmount)
        val undo: View = view.findViewById(R.id.financeTransactionUndo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_finance_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tx = items[position]
        holder.description.text = tx.description
        holder.meta.text = "${tx.source} • ${formatter.format(Date(tx.timestamp))}${if (tx.automatic) " • automatisch" else ""}"
        val prefix = if (tx.kind == FinanceStore.Kind.EXPENSE) "−" else "+"
        holder.amount.text = prefix + NumberFormat.getCurrencyInstance(Locale("nl", "NL")).format(tx.amountCents / 100.0)
        holder.amount.setTextColor(
            ContextCompat.getColor(holder.itemView.context, if (tx.kind == FinanceStore.Kind.EXPENSE) R.color.amber else R.color.sage)
        )
        holder.undo.setOnClickListener { onUndo(tx) }
    }
}
