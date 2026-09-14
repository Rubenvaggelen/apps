package com.gmailorg.hub

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class CurrencyActivity : AppCompatActivity() {

    private val currencies = arrayOf("EUR", "SRD", "USD")
    private lateinit var rateStatusText: TextView
    private lateinit var amountInput: EditText
    private lateinit var resultText: TextView
    private lateinit var sourceButton: Button
    private lateinit var targetButton: Button

    private var rates: CurrencyRateFetcher.Rates? = null
    private var sourceCurrency = "EUR"
    private var targetCurrency = "SRD"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency)
        MenuButtonHelper.attach(this)

        rateStatusText = findViewById(R.id.rateStatusText)
        amountInput = findViewById(R.id.amountInput)
        resultText = findViewById(R.id.resultText)
        sourceButton = findViewById(R.id.sourceCurrencyButton)
        targetButton = findViewById(R.id.targetCurrencyButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.refreshRateButton).setOnClickListener { loadRate() }
        findViewById<View>(R.id.swapCurrenciesButton).setOnClickListener {
            val old = sourceCurrency
            sourceCurrency = targetCurrency
            targetCurrency = old
            refreshCurrencyLabels()
            updateRateStatus()
            calculate()
        }
        sourceButton.setOnClickListener { chooseCurrency(true) }
        targetButton.setOnClickListener { chooseCurrency(false) }

        amountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { calculate() }
        })

        refreshCurrencyLabels()
        loadRate()
    }

    private fun chooseCurrency(source: Boolean) {
        val current = if (source) sourceCurrency else targetCurrency
        val checked = currencies.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(if (source) "Van welke valuta?" else "Naar welke valuta?")
            .setSingleChoiceItems(currencies, checked) { dialog, which ->
                val chosen = currencies[which]
                if (source) {
                    sourceCurrency = chosen
                    // USD-koers wordt standaard tegenover SRD getoond.
                    if (chosen == "USD") targetCurrency = "SRD"
                } else {
                    targetCurrency = chosen
                    if (chosen == "USD") sourceCurrency = "SRD"
                }
                dialog.dismiss()
                refreshCurrencyLabels()
                updateRateStatus()
                calculate()
            }
            .show()
    }

    private fun refreshCurrencyLabels() {
        sourceButton.text = sourceCurrency
        targetButton.text = targetCurrency
        amountInput.hint = "Bedrag in $sourceCurrency"
    }

    private fun loadRate() {
        rateStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        rateStatusText.text = "Koersen ophalen..."
        CurrencyRateFetcher.fetchRate { outcome ->
            when (outcome) {
                is CurrencyRateFetcher.RateOutcome.Success -> {
                    rates = outcome.rates
                    rateStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_main))
                    updateRateStatus()
                    calculate()
                }
                is CurrencyRateFetcher.RateOutcome.Error -> {
                    rateStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                    rateStatusText.text = outcome.message
                }
            }
        }
    }


    private fun updateRateStatus() {
        val currentRates = rates ?: return
        val sourcePerEur = currentRates.perEur(sourceCurrency) ?: return
        val targetPerEur = currentRates.perEur(targetCurrency) ?: return
        val pairRate = targetPerEur / sourcePerEur

        rateStatusText.text = if (sourceCurrency == targetCurrency) {
            "1 $sourceCurrency = 1.00 $targetCurrency"
        } else {
            "Huidige koers: 1 $sourceCurrency = ${formatRate(pairRate)} $targetCurrency"
        }
    }

    private fun formatRate(value: Double): String {
        return when {
            value >= 100 -> String.format(Locale.US, "%.2f", value)
            value >= 1 -> String.format(Locale.US, "%.4f", value)
            else -> String.format(Locale.US, "%.6f", value)
        }
    }

    private fun calculate() {
        val value = amountInput.text.toString().replace(",", ".").toDoubleOrNull()
        if (value == null) {
            resultText.text = "—"
            return
        }
        if (sourceCurrency == targetCurrency) {
            resultText.text = "${format(value)} $targetCurrency"
            return
        }
        val currentRates = rates ?: run {
            resultText.text = "Koers laden..."
            return
        }
        val sourcePerEur = currentRates.perEur(sourceCurrency) ?: return
        val targetPerEur = currentRates.perEur(targetCurrency) ?: return
        val converted = value / sourcePerEur * targetPerEur
        resultText.text = "${format(converted)} $targetCurrency"
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
