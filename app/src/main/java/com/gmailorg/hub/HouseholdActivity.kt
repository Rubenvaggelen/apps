package com.gmailorg.hub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HouseholdActivity : AppCompatActivity() {

    private lateinit var adapter: ShoppingAdapter
    private lateinit var emptyState: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_household)
        ShoppingListStore.init(applicationContext)

        emptyState = findViewById(R.id.emptyState)
        input = findViewById(R.id.newItemInput)

        val list = findViewById<RecyclerView>(R.id.itemList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ShoppingAdapter(
            onToggle = { item -> ShoppingListStore.toggleDone(item.id); refresh() },
            onDelete = { item -> ShoppingListStore.remove(item.id); refresh() }
        )
        list.adapter = adapter

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.addItemButton).setOnClickListener { addCurrentInput() }
        findViewById<View>(R.id.clearDoneButton).setOnClickListener {
            ShoppingListStore.clearDone()
            refresh()
        }

        // Toevoegen zodra je op de "Klaar"-toets van het toetsenbord tikt —
        // dit is het "typ 'brood' en het komt automatisch op de lijst"-gedrag.
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCurrentInput()
                true
            } else {
                false
            }
        }

        refresh()
    }

    private fun addCurrentInput() {
        val text = input.text.toString()
        if (text.isBlank()) return
        ShoppingListStore.add(text)
        input.text.clear()
        refresh()
    }

    private fun refresh() {
        val items = ShoppingListStore.getAll()
        adapter.updateItems(items)
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}

class ShoppingAdapter(
    private val onToggle: (ShoppingItem) -> Unit,
    private val onDelete: (ShoppingItem) -> Unit
) : RecyclerView.Adapter<ShoppingAdapter.ViewHolder>() {

    private var items: List<ShoppingItem> = emptyList()

    fun updateItems(newItems: List<ShoppingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.itemCheckbox)
        val text: TextView = view.findViewById(R.id.itemText)
        val deleteButton: ImageButton = view.findViewById(R.id.itemDeleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shopping, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text.text = item.text
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = item.done
        applyDoneStyle(holder.text, item.done)

        holder.checkbox.setOnCheckedChangeListener { _, _ -> onToggle(item) }
        holder.deleteButton.setOnClickListener { onDelete(item) }
    }

    private fun applyDoneStyle(view: TextView, done: Boolean) {
        if (done) {
            view.paintFlags = view.paintFlags or android.graphics.Paint.STRIKE_THRU_FLAG
            view.alpha = 0.5f
        } else {
            view.paintFlags = view.paintFlags and android.graphics.Paint.STRIKE_THRU_FLAG.inv()
            view.alpha = 1.0f
        }
    }
}
