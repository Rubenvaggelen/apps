package com.gmailorg.hub

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

/**
 * Recepten-scherm — werkt op precies dezelfde manier als "Vraag het": de
 * ingetypte of ingesproken gerechtnaam wordt als vraag naar Gemini gestuurd,
 * en alleen het antwoord (het recept) wordt getoond.
 */
class RecipesActivity : AppCompatActivity() {

    private lateinit var recipeInput: EditText
    private lateinit var answerText: TextView

    private val voiceRecognition = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spokenText.isNullOrBlank()) {
            recipeInput.setText(spokenText)
            searchRecipe()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recipes)

        recipeInput = findViewById(R.id.recipeInput)
        answerText = findViewById(R.id.answerText)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.recipeSearchButton).setOnClickListener { searchRecipe() }
        findViewById<View>(R.id.voiceInputButton).setOnClickListener { startVoiceInput() }
        recipeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchRecipe()
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Spreek een gerecht in...")
        }
        if (intent.resolveActivity(packageManager) != null) {
            voiceRecognition.launch(intent)
        } else {
            Toast.makeText(this, "Geen spraakherkenning beschikbaar op dit toestel.", Toast.LENGTH_LONG).show()
        }
    }

    private fun searchRecipe() {
        val dish = recipeInput.text.toString().trim()
        if (dish.isBlank()) return

        answerText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        answerText.text = "Recept opzoeken..."

        val prompt = "Geef mij een recept voor: $dish. " +
            "Vermeld eerst de ingredi\u00ebnten met hoeveelheden (voor ongeveer 4 personen), " +
            "en daarna de bereidingswijze in genummerde stappen. Antwoord in het Nederlands."

        ChatGptClient.ask(prompt) { outcome ->
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
