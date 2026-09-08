package com.gmailorg.hub

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class NotificationsActivity : AppCompatActivity() {

    private lateinit var adapter: NotifAdapter
    private var activeFilter: String? = null // null = alle apps

    private val storeListener: () -> Unit = { runOnUiThread { refreshList() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        NotifStore.init(applicationContext)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.notifList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = NotifAdapter(emptyList()) { item -> NotifStore.removeByKey(item.key) }
        list.adapter = adapter

        // Swipe-om-te-wissen
        val swipeHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val item = currentFilteredItems().getOrNull(position) ?: return
                NotifStore.removeByKey(item.key)
            }
        })
        swipeHelper.attachToRecyclerView(list)

        findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener {
            refreshList()
            findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing = false
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            NotifStore.clearAll()
        }

        findViewById<Button>(R.id.grantAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        NotifStore.subscribe(storeListener)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        refreshList()
    }

    override fun onDestroy() {
        super.onDestroy()
        NotifStore.unsubscribe(storeListener)
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabled.contains(packageName)
    }

    private fun updatePermissionBanner() {
        findViewById<LinearLayout>(R.id.permissionBanner).visibility =
            if (isListenerEnabled()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun currentFilteredItems(): List<NotifItem> {
        val all = NotifStore.getAll()
        return if (activeFilter == null) all else all.filter { it.packageName == activeFilter }
    }

    private fun refreshList() {
        rebuildAppChips()
        val filtered = currentFilteredItems()
        adapter.updateItems(filtered)
        findViewById<TextView>(R.id.emptyState).visibility =
            if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun rebuildAppChips() {
        val row = findViewById<LinearLayout>(R.id.appChipsRow)
        row.removeAllViews()

        val distinctApps = NotifStore.getAll()
            .map { it.packageName to it.appLabel }
            .distinct()
            .sortedBy { it.second }

        row.addView(makeChip("Alles", activeFilter == null) {
            activeFilter = null
            refreshList()
        })

        distinctApps.forEach { (pkg, label) ->
            row.addView(makeChip(label, activeFilter == pkg) {
                activeFilter = pkg
                refreshList()
            })
        }
    }

    private fun makeChip(label: String, active: Boolean, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 13f
        tv.setPadding(32, 16, 32, 16)
        tv.setTextColor(getColor(if (active) R.color.on_amber else R.color.text_dim))
        tv.setBackgroundColor(getColor(if (active) R.color.amber else R.color.surface))
        tv.gravity = Gravity.CENTER
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.marginEnd = 20
        tv.layoutParams = params
        tv.setOnClickListener { onClick() }
        return tv
    }
}
