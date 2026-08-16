package dev.vynkor.agent

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.agent.ChatMessage
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val onCopy: (ChatMessage) -> Unit,
    private val onSpeak: (ChatMessage) -> Unit,
) : RecyclerView.Adapter<ChatAdapter.Holder>() {

    private var items: MutableList<ChatMessage> = mutableListOf()
    private var speaking: ChatMessage? = null

    fun submit(messages: MutableList<ChatMessage>) {
        items = messages
        speaking = null
        notifyDataSetChanged()
    }

    fun append(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    /** Marks [message] as currently spoken (or none) and refreshes the
     * affected rows so the speak button reflects the toggle state. */
    fun setSpeaking(message: ChatMessage?) {
        if (speaking == message) return
        val prev = speaking
        speaking = message
        prev?.let { p -> items.indexOf(p).takeIf { it >= 0 }?.let { notifyItemChanged(it) } }
        message?.let { m -> items.indexOf(m).takeIf { it >= 0 }?.let { notifyItemChanged(it) } }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], speaking == items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val ctx = view.context
        private val bubble: View = view.findViewById(R.id.bubble)
        private val text: TextView = view.findViewById(R.id.messageText)
        private val time: TextView = view.findViewById(R.id.messageTime)
        private val copy: ImageButton = view.findViewById(R.id.copyAction)
        private val speak: ImageButton = view.findViewById(R.id.speakAction)
        private var markwon: Markwon? = null
        private val timeFormat =
            SimpleDateFormat(ctx.getString(R.string.time_format_today), Locale.getDefault())

        fun bind(message: ChatMessage, isSpeaking: Boolean) {
            time.text = timeFormat.format(Date(message.timestamp))

            val lp = bubble.layoutParams as FrameLayout.LayoutParams
            when (message.role) {
                "user" -> {
                    lp.gravity = Gravity.END
                    bubble.setBackgroundResource(R.drawable.bubble_user)
                    markwon().setMarkdown(text, message.content)
                    val onPrimary = color(R.color.on_primary)
                    text.setTextColor(onPrimary)
                    time.setTextColor(onPrimary)
                    copy.visibility = View.VISIBLE
                    copy.imageTintList = ColorStateList.valueOf(onPrimary)
                    speak.visibility = View.GONE
                }
                "error" -> {
                    lp.gravity = Gravity.CENTER
                    bubble.setBackgroundResource(R.drawable.bubble_error)
                    text.text = message.content
                    val error = color(R.color.error)
                    text.setTextColor(error)
                    time.setTextColor(error)
                    copy.visibility = View.GONE
                    speak.visibility = View.GONE
                }
                else -> {
                    lp.gravity = Gravity.START
                    bubble.setBackgroundResource(R.drawable.bubble_assistant)
                    markwon().setMarkdown(text, message.content)
                    text.setTextColor(color(R.color.on_surface))
                    time.setTextColor(color(R.color.on_surface_variant))
                    copy.visibility = View.VISIBLE
                    copy.imageTintList = ColorStateList.valueOf(color(R.color.on_surface_variant))
                    speak.visibility = View.VISIBLE
                    speak.imageTintList = ColorStateList.valueOf(color(R.color.on_surface_variant))
                    speak.contentDescription = ctx.getString(
                        if (isSpeaking) R.string.stop_speak else R.string.speak_message
                    )
                }
            }
            bubble.layoutParams = lp

            copy.setOnClickListener { onCopy(message) }
            speak.setOnClickListener { onSpeak(message) }
        }

        private fun markwon(): Markwon = markwon ?: Markwon.create(ctx).also { markwon = it }

        private fun color(resId: Int): Int = ContextCompat.getColor(ctx, resId)
    }
}
