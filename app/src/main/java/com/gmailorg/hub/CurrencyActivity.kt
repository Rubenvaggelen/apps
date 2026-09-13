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
                if (source) sourceCurrency = currencies[which] else targetCurrency = currencies[which]
                dialog.dismiss()
                refreshCurrencyLabels()
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
                    rateStatusText.text = "1 EUR = ${format(outcome.rates.eurToSrd)} SRD  •  1 EUR = ${format(outcome.rates.eurToUsd)} USD"
                    calculate()
                }
                is CurrencyRateFetcher.RateOutcome.Error -> {
                    rateStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                    rateStatusText.text = outcome.message
                }
            }
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
