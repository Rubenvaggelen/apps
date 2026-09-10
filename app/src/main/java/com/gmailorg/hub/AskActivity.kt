package com.gmailorg.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class AskActivity : AppCompatActivity() {

    private lateinit var questionInput: EditText
    private lateinit var answerText: TextView

    private val voiceRecognition = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spokenText.isNullOrBlank()) {
            questionInput.setText(spokenText)
            askQuestion()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ask)

        questionInput = findViewById(R.id.questionInput)
        answerText = findViewById(R.id.answerText)

        // Laat de tekst ook los selecteerbaar zijn (geeft ook het standaard Android selectie-menu)
        answerText.setTextIsSelectable(true)

        // Lang indrukken op het antwoord = hele tekst kopiëren naar klembord
        answerText.setOnLongClickListener {
            val text = answerText.text.toString()
            if (text.isNotBlank() && text != "Even denken...") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Antwoord", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Gekopieerd naar klembord", Toast.LENGTH_SHORT).show()
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.askButton).setOnClickListener { askQuestion() }
        findViewById<View>(R.id.voiceInputButton).setOnClickListener { startVoiceInput() }
        questionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                askQuestion()
                true
            } else {
                false
            }
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Spreek je vraag in...")
        }
        if (intent.resolveActivity(packageManager) != null) {
            voiceRecognition.launch(intent)
        } else {
            Toast.makeText(this, "Geen spraakherkenning beschikbaar op dit toestel.", Toast.LENGTH_LONG).show()
        }
    }

    private fun askQuestion() {
        val question = questionInput.text.toString().trim()
        if (question.isBlank()) return

        answerText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        answerText.text = "Even denken..."

        ChatGptClient.ask(question) { outcome ->
            when (outcome) {
                is ChatGptClient.AskOutcome.Success -> {
                    answerText.setTextColor(ContextCompat.getColor(this, R.color.text_main))
                    answerText.text = outcome.answer
                }
                is ChatGptClient.AskOutcome.Error -> {
                    answerText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                    answerText.text = outcome.message
                }
            }
        }
    }
}
