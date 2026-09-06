package com.gmailorg.hub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
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
    private lateinit var movieTitleInput: EditText
    private lateinit var movieSearchButton: View
    private lateinit var movieResultContainer: LinearLayout
    private lateinit var notificationSoundName: TextView
    private lateinit var googleTasksStatus: TextView
    private lateinit var googleTasksLinkButton: android.widget.Button

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

    private val pickRingtone = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        NotificationSoundStore.setSoundUri(this, uri)
        refreshSoundName()
        Toast.makeText(this, "Meldingsgeluid ingesteld", Toast.LENGTH_SHORT).show()
    }

    private val googleSignIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val email = GoogleTasksAuth.handleSignInResult(this, result.data)
        if (email != null) {
            GoogleTasksSyncWorker.schedule(this)
            Thread {
                GoogleTasksSync.syncNow(applicationContext)
                runOnUiThread { refresh() }
            }.start()
            Toast.makeText(this, "Gekoppeld aan $email", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Koppelen mislukt of geannuleerd", Toast.LENGTH_SHORT).show()
        }
        refreshGoogleTasksStatus()
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

        movieTitleInput = findViewById(R.id.movieTitleInput)
        movieSearchButton = findViewById(R.id.movieSearchButton)
        movieResultContainer = findViewById(R.id.movieResultContainer)
        notificationSoundName = findViewById(R.id.notificationSoundName)
        refreshSoundName()

        googleTasksStatus = findViewById(R.id.googleTasksStatus)
        googleTasksLinkButton = findViewById(R.id.googleTasksLinkButton)
        refreshGoogleTasksStatus()
        googleTasksLinkButton.setOnClickListener {
            if (GoogleTasksAuth.isLinked(this)) {
                GoogleTasksAuth.unlink(this)
                GoogleTasksSyncWorker.cancel(this)
                refreshGoogleTasksStatus()
                Toast.makeText(this, "Ontkoppeld", Toast.LENGTH_SHORT).show()
            } else {
                googleSignIn.launch(GoogleTasksAuth.getSignInIntent(this))
            }
        }

        findViewById<View>(R.id.chooseSoundButton).setOnClickListener { openRingtonePicker() }

        movieSearchButton.setOnClickListener { searchMovie() }
        movieTitleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchMovie()
                true
            } else {
                false
            }
        }

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
            }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (GoogleTasksAuth.isLinked(this)) {
            Thread {
                GoogleTasksSync.syncNow(applicationContext)
                runOnUiThread { refresh() }
            }.start()
        }
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
        // Nu we net locatietoestemming hebben gekregen, meteen ook de
        // opgeslagen parkeeradressen registreren.
        ParkingGeofenceManager.syncAll(this)
    }

    private fun searchMovie() {
        val query = movieTitleInput.text.toString().trim()
        if (query.isBlank()) return

        movieResultContainer.removeAllViews()
        movieResultContainer.visibility = View.VISIBLE
        addResultLine("Zoeken naar \u201c$query\u201d...", dim = true)

        MovieLookup.search(query) { outcome ->
            movieResultContainer.removeAllViews()
            when (outcome) {
                is MovieLookup.LookupOutcome.Success -> showMovieResult(outcome.result)
                is MovieLookup.LookupOutcome.NotFound ->
                    addResultLine("Geen film gevonden voor \u201c${outcome.query}\u201d.", dim = true)
                is MovieLookup.LookupOutcome.Error ->
                    addResultLine(outcome.message, dim = true)
            }
        }
    }

    private fun showMovieResult(result: MovieLookup.MovieResult) {
        val suffix = if (result.isSeries) " (serie)" else ""
        val titleLine = if (result.year != null) "${result.title} (${result.year})$suffix" else "${result.title}$suffix"
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
            textSize = if (bold) 15f else 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        }
        movieResultContainer.addView(view)
    }

    private fun refreshGoogleTasksStatus() {
        val email = GoogleTasksAuth.linkedAccountEmail(this)
        if (email != null) {
            googleTasksStatus.text = "Gekoppeld: $email"
            googleTasksLinkButton.text = "Ontkoppelen"
        } else {
            googleTasksStatus.text = "Niet gekoppeld"
            googleTasksLinkButton.text = "Koppelen"
        }
    }

    private fun openRingtonePicker() {
        val currentUri = NotificationSoundStore.getSoundUri(this)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Kies meldingsgeluid")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
        }
        pickRingtone.launch(intent)
    }

    private fun refreshSoundName() {
        notificationSoundName.text = NotificationSoundStore.getSoundDisplayName(this)
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
