package dev.vynkor.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.agent.Chat

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

        fun bind(chat: Chat) {
            val ctx = title.context
            title.text = chat.title.ifBlank { ctx.getString(R.string.new_chat) }
            preview.text = chat.messages.lastOrNull()?.content.orEmpty()
            itemView.setOnClickListener { onOpen(chat) }
            itemView.setOnLongClickListener {
                onLongPress(chat)
                true
            }
        }
    }
}
