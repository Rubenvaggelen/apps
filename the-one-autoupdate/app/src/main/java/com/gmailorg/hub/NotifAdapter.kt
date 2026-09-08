package com.gmailorg.hub

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class NotifAdapter(
    private var items: List<NotifItem>,
    private val onDismiss: (NotifItem) -> Unit
) : RecyclerView.Adapter<NotifAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale("nl", "NL"))
    // Houdt bij welk item op dit moment het antwoordveld open heeft staan.
    private var openReplyKey: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appLabel: android.widget.TextView = view.findViewById(R.id.appLabel)
        val timeLabel: android.widget.TextView = view.findViewById(R.id.timeLabel)
        val titleLabel: android.widget.TextView = view.findViewById(R.id.titleLabel)
        val textLabel: android.widget.TextView = view.findViewById(R.id.textLabel)
        val replyRow: android.widget.LinearLayout = view.findViewById(R.id.replyRow)
        val replyInput: android.widget.EditText = view.findViewById(R.id.replyInput)
        val replySendButton: android.widget.Button = view.findViewById(R.id.replySendButton)
    }

    fun updateItems(newItems: List<NotifItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.appLabel.text = item.appLabel
        holder.timeLabel.text = timeFormat.format(Date(item.postTime))
        holder.titleLabel.text = item.title
        holder.textLabel.text = item.text

        if (item.hasReplyAction) {
            holder.replyRow.visibility = if (openReplyKey == item.key) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                openReplyKey = if (openReplyKey == item.key) null else item.key
                notifyDataSetChanged()
            }
            holder.replySendButton.setOnClickListener {
                val text = holder.replyInput.text.toString().trim()
                if (text.isEmpty()) return@setOnClickListener
                val ok = UnifiedNotificationListener.sendReply(item.key, text)
                val context = holder.itemView.context
                if (ok) {
                    Toast.makeText(context, "Verstuurd", Toast.LENGTH_SHORT).show()
                    holder.replyInput.text.clear()
                    openReplyKey = null
                    onDismiss(item)
                } else {
                    Toast.makeText(context, "Versturen mislukt — open de app zelf om te antwoorden", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            holder.replyRow.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
        }
    }
}
