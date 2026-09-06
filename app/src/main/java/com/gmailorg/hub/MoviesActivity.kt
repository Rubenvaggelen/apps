package com.gmailorg.hub

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MoviesActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var searchButton: View
    private lateinit var resultContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movies)

        titleInput = findViewById(R.id.movieTitleInput)
        searchButton = findViewById(R.id.movieSearchButton)
        resultContainer = findViewById(R.id.movieResultContainer)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        searchButton.setOnClickListener { searchMovie() }
        titleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchMovie()
                true
            } else {
                false
            }
        }
    }

    private fun searchMovie() {
        val query = titleInput.text.toString().trim()
        if (query.isBlank()) return

        resultContainer.removeAllViews()
        resultContainer.visibility = View.VISIBLE
        addResultLine("Zoeken naar “$query”…", dim = true)

        MovieLookup.search(query) { outcome ->
            resultContainer.removeAllViews()
            when (outcome) {
                is MovieLookup.LookupOutcome.Success -> showMovieResult(outcome.result)
                is MovieLookup.LookupOutcome.NotFound ->
                    addResultLine("Geen film of serie gevonden voor “${outcome.query}”.", dim = true)
                is MovieLookup.LookupOutcome.Error ->
                    addResultLine(outcome.message, dim = true)
            }
        }
    }

    private fun showMovieResult(result: MovieLookup.MovieResult) {
        val type = if (result.isSeries) "Serie" else "Film"
        addResultLine(type, dim = true)
        val titleLine = if (result.year != null) "${result.title} (${result.year})" else result.title
        addResultLine(titleLine, bold = true)

        if (result.availableOn.isEmpty()) {
            addResultLine("Niet gevonden op een van je streamingdiensten in Nederland.", dim = true)
        } else {
            addResultLine("Beschikbaar op: ${result.availableOn.joinToString(", ")}")
        }
    }

    private fun addResultLine(text: String, bold: Boolean = false, dim: Boolean = false) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, if (dim) R.color.text_dim else R.color.text_main))
            textSize = if (bold) 17f else 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }
        resultContainer.addView(view)
    }
}
