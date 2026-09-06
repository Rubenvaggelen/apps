package com.gmailorg.hub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PIN_CODE = "290114"
    }

    private lateinit var lockSection: LinearLayout
    private lateinit var unlockedSection: LinearLayout
    private lateinit var pinInput: EditText
    private lateinit var pinErrorText: TextView
    private lateinit var parkingEmptyState: TextView
    private lateinit var adapter: SettingsParkingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ParkingAddressStore.init(applicationContext)

        lockSection = findViewById(R.id.lockSection)
        unlockedSection = findViewById(R.id.unlockedSection)
        pinInput = findViewById(R.id.pinInput)
        pinErrorText = findViewById(R.id.pinErrorText)
        parkingEmptyState = findViewById(R.id.parkingEmptyState)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.unlockButton).setOnClickListener { tryUnlock() }
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryUnlock()
                true
            } else {
                false
            }
        }

        val list = findViewById<RecyclerView>(R.id.parkingAddressList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = SettingsParkingAdapter(
            onDelete = { address ->
                ParkingAddressStore.remove(address.id)
                ParkingGeofenceManager.unregister(this, address.id)
                refreshParkingList()
            }
        )
        list.adapter = adapter
    }

    private fun tryUnlock() {
        if (pinInput.text.toString() == PIN_CODE) {
            pinErrorText.visibility = View.GONE
            lockSection.visibility = View.GONE
            unlockedSection.visibility = View.VISIBLE
            refreshParkingList()
        } else {
            pinErrorText.visibility = View.VISIBLE
            pinInput.text.clear()
        }
    }

    private fun refreshParkingList() {
        val items = ParkingAddressStore.getAll()
        adapter.updateItems(items)
        parkingEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}

class SettingsParkingAdapter(
    private val onDelete: (ParkingAddress) -> Unit
) : RecyclerView.Adapter<SettingsParkingAdapter.ViewHolder>() {

    private var items: List<ParkingAddress> = emptyList()

    fun updateItems(newItems: List<ParkingAddress>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.addressText)
        val deleteButton: ImageButton = view.findViewById(R.id.addressDeleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_parking_address, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text.text = item.address
        holder.deleteButton.setOnClickListener { onDelete(item) }
    }
}
