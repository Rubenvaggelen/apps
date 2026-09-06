package com.gmailorg.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Zoekt een recept op via TheMealDB — een gratis, publieke recepten-database
 * die geen API key vereist. Gebruikt voor de "Recepten"-tegel.
 */
object RecipeLookup {

    private const val TAG = "RecipeLookup"

    data class Recipe(
        val title: String,
        val category: String?,
        val ingredients: List<String>,
        val instructions: String
    )

    sealed class LookupOutcome {
        data class Success(val recipe: Recipe) : LookupOutcome()
        data class NotFound(val query: String) : LookupOutcome()
        data class Error(val message: String) : LookupOutcome()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun search(query: String, callback: (LookupOutcome) -> Unit) {
        Thread {
            try {
                // De database is Engelstalig — vertaal de (Nederlandse) zoekterm
                // eerst naar het Engels zodat je gewoon in het Nederlands kunt zoeken.
                val englishQuery = Translator.translate(query, "nl", "en")
                var recipe = fetchRecipe(englishQuery)
                if (recipe == null && !englishQuery.equals(query, ignoreCase = true)) {
                    // Val terug op de oorspronkelijke tekst, voor het geval de
                    // vertaling een ongebruikelijke term opleverde.
                    recipe = fetchRecipe(query)
                }
                if (recipe == null) {
                    mainHandler.post { callback(LookupOutcome.NotFound(query)) }
                } else {
                    val dutchRecipe = translateRecipeToDutch(recipe)
                    mainHandler.post { callback(LookupOutcome.Success(dutchRecipe)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recept opzoeken mislukt", e)
                mainHandler.post {
                    callback(LookupOutcome.Error("Opzoeken mislukt: ${e.message ?: "onbekende fout"}"))
                }
            }
        }.start()
    }

    /** Vertaalt titel, categorie, ingrediënten en bereiding naar het Nederlands. */
    private fun translateRecipeToDutch(recipe: Recipe): Recipe {
        val translatedTitle = Translator.translate(recipe.title, "en", "nl")
        val translatedCategory = recipe.category?.let { Translator.translate(it, "en", "nl") }

        // Ingrediënten samen vertalen (gescheiden door een teken dat niet
        // vertaald wordt), zodat we niet voor elk item apart een aanroep nodig hebben.
        val translatedIngredients = if (recipe.ingredients.isEmpty()) {
            emptyList()
        } else {
            val joined = recipe.ingredients.joinToString(" || ")
            Translator.translate(joined, "en", "nl").split("||").map { it.trim() }
                .let { if (it.size == recipe.ingredients.size) it else recipe.ingredients }
        }

        // Instructies in stukken van max. ~450 tekens vertalen (limiet van de gratis API).
        val translatedInstructions = if (recipe.instructions.isBlank()) {
            recipe.instructions
        } else {
            recipe.instructions.chunked(450).joinToString(" ") { chunk ->
                Translator.translate(chunk, "en", "nl")
            }
        }

        return Recipe(
            title = translatedTitle,
            category = translatedCategory,
            ingredients = translatedIngredients,
            instructions = translatedInstructions
        )
    }

    private fun fetchRecipe(query: String): Recipe? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.themealdb.com/api/json/v1/1/search.php?s=$encoded")
        val body = get(url)
        val json = JSONObject(body)
        val meals = json.optJSONArray("meals") ?: return null
        if (meals.length() == 0) return null

        val meal = meals.getJSONObject(0)
        val title = meal.optString("strMeal", query)
        val category = meal.optString("strCategory", null)
        val instructions = meal.optString("strInstructions", "").trim()

        val ingredients = mutableListOf<String>()
        for (i in 1..20) {
            val ingredient = meal.optString("strIngredient$i", "").trim()
            val measure = meal.optString("strMeasure$i", "").trim()
            if (ingredient.isNotEmpty()) {
                ingredients.add(if (measure.isNotEmpty()) "$measure $ingredient" else ingredient)
            }
        }

        return Recipe(
            title = title,
            category = category,
            ingredients = ingredients,
            instructions = instructions
        )
    }

    private fun get(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
