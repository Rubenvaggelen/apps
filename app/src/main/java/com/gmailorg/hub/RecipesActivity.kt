package com.gmailorg.hub

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class RecipesActivity : AppCompatActivity() {

    private lateinit var recipeInput: EditText
    private lateinit var resultContainer: LinearLayout

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
        resultContainer = findViewById(R.id.recipeResultContainer)

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
        val query = recipeInput.text.toString().trim()
        if (query.isBlank()) return

        resultContainer.removeAllViews()
        addResultLine("Zoeken naar \u201c$query\u201d...", dim = true)

        RecipeLookup.search(query) { outcome ->
            resultContainer.removeAllViews()
            when (outcome) {
                is RecipeLookup.LookupOutcome.Success -> showRecipe(outcome.recipe)
                is RecipeLookup.LookupOutcome.NotFound ->
                    addResultLine("Geen recept gevonden voor \u201c${outcome.query}\u201d. Probeer eventueel de Engelse naam.", dim = true)
                is RecipeLookup.LookupOutcome.Error ->
                    addResultLine(outcome.message, dim = true)
            }
        }
    }

    private fun showRecipe(recipe: RecipeLookup.Recipe) {
        addResultLine(recipe.title, bold = true)
        if (!recipe.category.isNullOrBlank()) {
            addResultLine(recipe.category, dim = true)
        }

        addResultLine("Ingredi\u00ebnten", bold = true, topSpacing = true)
        recipe.ingredients.forEach { addResultLine("\u2022 $it") }

        addResultLine("Bereiding", bold = true, topSpacing = true)
        addResultLine(recipe.instructions)
    }

    private fun addResultLine(text: String, bold: Boolean = false, dim: Boolean = false, topSpacing: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, if (dim) R.color.text_dim else R.color.text_main))
            textSize = if (bold) 16f else 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            val topPad = if (topSpacing) 16 else 0
            setPadding(0, topPad, 0, 6)
        }
        resultContainer.addView(view)
    }
}
