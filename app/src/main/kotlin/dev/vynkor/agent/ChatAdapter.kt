package dev.vynkor.agent

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.agent.ChatMessage

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {

    private val items = mutableListOf<ChatMessage>()

    fun append(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.messageText)

        fun bind(message: ChatMessage) {
            val ctx = text.context
            text.text = message.content
            val lp = text.layoutParams as FrameLayout.LayoutParams
            when (message.role) {
                "user" -> {
                    lp.gravity = Gravity.END
                    text.setBackgroundResource(R.drawable.bubble_user)
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.on_primary))
                }
                "error" -> {
                    lp.gravity = Gravity.CENTER
                    text.setBackgroundResource(R.drawable.bubble_error)
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.error))
                }
                else -> {
                    lp.gravity = Gravity.START
                    text.setBackgroundResource(R.drawable.bubble_assistant)
                    text.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                }
            }
            text.layoutParams = lp
        }
    }
}
