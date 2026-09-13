package com.gmailorg.hub

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Recepten-scherm — werkt op precies dezelfde manier als "Vraag het": de
 * ingetypte of ingesproken gerechtnaam wordt als vraag naar Groq AI gestuurd,
 * en het antwoord (het recept) wordt getoond. De ingrediënten (herkenbaar
 * aan het "INGREDIENTEN:"/"BEREIDING:"-format dat we in de prompt afdwingen)
 * kunnen met één knop naar de boodschappenlijst gestuurd worden.
 */
class RecipesActivity : AppCompatActivity() {

    private lateinit var recipeInput: EditText
    private lateinit var answerText: TextView
    private lateinit var addIngredientsButton: Button

    private var currentIngredients: List<String> = emptyList()

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
        ShoppingListStore.init(applicationContext)

        recipeInput = findViewById(R.id.recipeInput)
        answerText = findViewById(R.id.answerText)
        addIngredientsButton = findViewById(R.id.addIngredientsButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.recipeSearchButton).setOnClickListener { searchRecipe() }
        findViewById<View>(R.id.voiceInputButton).setOnClickListener { startVoiceInput() }
        addIngredientsButton.setOnClickListener { addIngredientsToShoppingList() }
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

        addIngredientsButton.visibility = View.GONE
        currentIngredients = emptyList()
        answerText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        answerText.text = "Recept zoeken..."

        // De bronselectie gebeurt automatisch: zoek de ingestelde websites en
        // toon direct één compleet recept. De gebruiker hoeft niets te kiezen.
        ChatGptClient.askRecipe(this, dish) { outcome ->
            when (outcome) {
                is ChatGptClient.AskOutcome.Success -> {
                    answerText.setTextColor(ContextCompat.getColor(this, R.color.text_main))
                    answerText.text = displayableRecipe(outcome.answer)
                    currentIngredients = parseShoppingListIngredients(outcome.answer)
                    addIngredientsButton.visibility = if (currentIngredients.isNotEmpty()) View.VISIBLE else View.GONE
                }
                is ChatGptClient.AskOutcome.Error -> {
                    answerText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                    answerText.text = outcome.message
                }
            }
        }
    }

    /**
     * Toon alleen het recept zelf. Bronkeuze en broninformatie blijven op de
     * achtergrond; de gebruiker krijgt gewoon één recept als antwoord.
     */
    private fun displayableRecipe(answer: String): String {
        val shoppingIndex = answer.indexOf("BOODSCHAPPENLIJST:", ignoreCase = true)
        var visible = if (shoppingIndex == -1) answer else answer.substring(0, shoppingIndex)

        val ingredientsIndex = visible.indexOf("INGREDIENTEN:", ignoreCase = true)
        if (ingredientsIndex >= 0) {
            visible = visible.substring(ingredientsIndex)
        } else {
            // Als Groq toch alleen een BRON:-regel vooraan zet, verberg die.
            visible = visible.lines()
                .dropWhile { line ->
                    val t = line.trim()
                    t.isBlank() || t.startsWith("BRON:", ignoreCase = true) ||
                        t.startsWith("http://", ignoreCase = true) ||
                        t.startsWith("https://", ignoreCase = true) ||
                        t.contains("sranangkukru.net", ignoreCase = true) ||
                        t.contains("leukerecepten.nl", ignoreCase = true)
                }
                .joinToString("\n")
        }

        return visible.trim()
    }

    /** Haalt de kale ingrediëntnamen uit de "BOODSCHAPPENLIJST:"-sectie (zonder hoeveelheden). */
    private fun parseShoppingListIngredients(answer: String): List<String> {
        val startIndex = answer.indexOf("BOODSCHAPPENLIJST:", ignoreCase = true)
        if (startIndex == -1) return parseIngredients(answer) // terugval op de gewone sectie
        val block = answer.substring(startIndex + "BOODSCHAPPENLIJST:".length)
        return block.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
    }

    /** Haalt de regels tussen "INGREDIENTEN:" en "BEREIDING:" eruit, elk beginnend met "- ". */
    private fun parseIngredients(answer: String): List<String> {
        val startIndex = answer.indexOf("INGREDIENTEN:", ignoreCase = true)
        val endIndex = answer.indexOf("BEREIDING:", ignoreCase = true)
        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) return emptyList()

        val block = answer.substring(startIndex + "INGREDIENTEN:".length, endIndex)
        return block.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun addIngredientsToShoppingList() {
        if (currentIngredients.isEmpty()) return
        currentIngredients.forEach { ShoppingListStore.add(it) }
        Toast.makeText(this, "${currentIngredients.size} ingrediënten toegevoegd aan boodschappenlijst", Toast.LENGTH_LONG).show()
        addIngredientsButton.visibility = View.GONE
    }
}
