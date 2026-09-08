package com.gmailorg.hub

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class CurrencyActivity : AppCompatActivity() {

    private lateinit var rateStatusText: TextView
    private lateinit var eurInput: EditText
    private lateinit var srdInput: EditText

    private var eurToSrd: Double? = null

    // Voorkomt een oneindige lus wanneer we het andere veld programmatisch bijwerken.
    private var isUpdatingProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_currency)

        rateStatusText = findViewById(R.id.rateStatusText)
        eurInput = findViewById(R.id.eurInput)
        srdInput = findViewById(R.id.srdInput)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.refreshRateButton).setOnClickListener { loadRate() }

        eurInput.addTextChangedListener(simpleWatcher {
            if (isUpdatingProgrammatically) return@simpleWatcher
            val rate = eurToSrd ?: return@simpleWatcher
            val eur = eurInput.text.toString().replace(",", ".").toDoubleOrNull()
            isUpdatingProgrammatically = true
            srdInput.setText(if (eur != null) formatAmount(eur * rate) else "")
            isUpdatingProgrammatically = false
        })

        srdInput.addTextChangedListener(simpleWatcher {
            if (isUpdatingProgrammatically) return@simpleWatcher
            val rate = eurToSrd ?: return@simpleWatcher
            val srd = srdInput.text.toString().replace(",", ".").toDoubleOrNull()
            isUpdatingProgrammatically = true
            eurInput.setText(if (srd != null) formatAmount(srd / rate) else "")
            isUpdatingProgrammatically = false
        })

        loadRate()
    }

    private fun loadRate() {
        rateStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        rateStatusText.text = "Koers ophalen..."

        CurrencyRateFetcher.fetchRate { outcome ->
            when (outcome) {
                is CurrencyRateFetcher.RateOutcome.Success -> {
                    eurToSrd = outcome.eurToSrd
                    rateStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_main))
                    rateStatusText.text = "1 EUR = ${formatAmount(outcome.eurToSrd)} SRD"
                }
                is CurrencyRateFetcher.RateOutcome.Error -> {
                    rateStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                    rateStatusText.text = outcome.message
                }
            }
        }
    }

    private fun formatAmount(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun simpleWatcher(onChanged: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChanged() }
    }
}
