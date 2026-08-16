package dev.vynkor.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.agent.Chat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatListAdapter(
    private val onOpen: (Chat) -> Unit,
    private val onLongPress: (Chat) -> Unit,
) : RecyclerView.Adapter<ChatListAdapter.Holder>() {

    private var items: List<Chat> = emptyList()

    fun submit(list: List<Chat>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.chatTitle)
        private val preview: TextView = view.findViewById(R.id.chatPreview)
        private val time: TextView = view.findViewById(R.id.chatTime)

        fun bind(chat: Chat) {
            val ctx = title.context
            title.text = chat.title.ifBlank { ctx.getString(R.string.new_chat) }
            preview.text = chat.messages.lastOrNull()?.content.orEmpty()
            time.text = formatTime(ctx, chat.updatedAt)
            itemView.setOnClickListener { onOpen(chat) }
            itemView.setOnLongClickListener {
                onLongPress(chat)
                true
            }
        }

        private fun formatTime(ctx: android.content.Context, timestamp: Long): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = timestamp }
            val pattern = if (
                now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            ) {
                ctx.getString(R.string.time_format_today)
            } else {
                ctx.getString(R.string.time_format_date)
            }
            return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
        }
    }
}
