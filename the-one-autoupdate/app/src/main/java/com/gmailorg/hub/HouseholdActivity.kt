package com.gmailorg.hub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HouseholdActivity : AppCompatActivity() {

    private lateinit var adapter: ShoppingAdapter
    private lateinit var emptyState: TextView
    private lateinit var input: EditText
    private lateinit var supermarketSwitch: Switch

    private val requestForegroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestBackgroundLocationIfNeeded()
        } else {
            supermarketSwitch.isChecked = false
            Toast.makeText(this, "Locatietoegang is nodig voor supermarkt-meldingen.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Ook zonder achtergrondlocatie werkt het gewoon terwijl de app open/actief is —
        // we gaan altijd door met het instellen van de meldingen.
        armSupermarketAlerts()
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* resultaat negeren, meldingen werken al voor Android 13+ als dit geweigerd wordt niet, dat is prima */ }

    private val voiceRecognition = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spokenText.isNullOrBlank()) {
            ShoppingListStore.add(spokenText)
            refresh()
            Toast.makeText(this, "Toegevoegd: $spokenText", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_household)
        ShoppingListStore.init(applicationContext)

        emptyState = findViewById(R.id.emptyState)
        input = findViewById(R.id.newItemInput)
        supermarketSwitch = findViewById(R.id.supermarketAlertSwitch)
        supermarketSwitch.isChecked = SupermarketGeofenceManager.isEnabled(this)

        val list = findViewById<RecyclerView>(R.id.itemList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ShoppingAdapter(
            onToggle = { item -> ShoppingListStore.toggleDone(item.id); refresh() },
            onDelete = { item -> ShoppingListStore.remove(item.id); refresh() }
        )
        list.adapter = adapter

        findViewById<View>(R.id.voiceInputButton).setOnClickListener { startVoiceInput() }

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

        supermarketSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ensureForegroundLocationThenArm()
            } else {
                SupermarketGeofenceManager.disable(this)
                SupermarketRefreshWorker.cancel(this)
            }
        }

        refresh()
    }

    private fun ensureForegroundLocationThenArm() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestBackgroundLocationIfNeeded()
        } else {
            requestForegroundLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }
        armSupermarketAlerts()
    }

    private fun armSupermarketAlerts() {
        Toast.makeText(this, "Supermarkten in de buurt zoeken...", Toast.LENGTH_SHORT).show()
        SupermarketGeofenceManager.enableForCurrentLocation(this) { success, message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (!success) supermarketSwitch.isChecked = false
        }
        SupermarketRefreshWorker.schedule(this)
        // Nu we net locatietoestemming hebben gekregen, meteen ook de
        // opgeslagen parkeeradressen registreren.
        ParkingGeofenceManager.syncAll(this)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Zeg wat je wilt toevoegen...")
        }
        if (intent.resolveActivity(packageManager) != null) {
            voiceRecognition.launch(intent)
        } else {
            Toast.makeText(this, "Geen spraakherkenning beschikbaar op dit toestel.", Toast.LENGTH_LONG).show()
        }
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
            view.paintFlags = view.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            view.alpha = 0.5f
        } else {
            view.paintFlags = view.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            view.alpha = 1.0f
        }
    }
}
