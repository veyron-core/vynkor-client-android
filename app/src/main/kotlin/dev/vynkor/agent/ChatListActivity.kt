package dev.vynkor.agent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dev.vynkor.agent.agent.Chat
import dev.vynkor.agent.agent.ChatStore
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore

class ChatListActivity : AppCompatActivity() {

    private lateinit var adapter: ChatListAdapter
    private lateinit var profile: HostProfile
    private lateinit var emptyState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_list)

        val active = ProfileStore.active(this)
        if (active == null) {
            Toast.makeText(this, R.string.no_profile, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        profile = active

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.chatList)
        emptyState = findViewById(R.id.emptyState)
        val newChat = findViewById<FloatingActionButton>(R.id.newChat)

        adapter = ChatListAdapter(
            onOpen = { chat -> openChat(chat) },
            onLongPress = { chat -> showChatMenu(chat) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        newChat.setOnClickListener { openChat(null) }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val chats = ChatStore.list(this, profile.id)
        adapter.submit(chats)
        emptyState.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openChat(chat: Chat?) {
        val intent = Intent(this, ChatActivity::class.java)
        if (chat != null) {
            intent.putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id)
        }
        startActivity(intent)
    }

    private fun showChatMenu(chat: Chat) {
        val options = arrayOf(
            getString(R.string.rename_chat),
            getString(R.string.delete_chat),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(chat.title.ifBlank { getString(R.string.new_chat) })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameChat(chat)
                    1 -> confirmDelete(chat)
                }
            }
            .show()
    }

    private fun renameChat(chat: Chat) {
        val input = EditText(this)
        input.hint = getString(R.string.rename_chat_hint)
        input.setText(chat.title)
        input.setSelection(input.text.length)
        val holder = FrameLayout(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        holder.setPadding(pad, pad, pad, 0)
        holder.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_chat)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = input.text.toString().trim()
                ChatStore.rename(this, profile.id, chat.id, title)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(chat: Chat) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_chat)
            .setMessage(R.string.delete_chat_confirm)
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                ChatStore.delete(this, profile.id, chat.id)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
