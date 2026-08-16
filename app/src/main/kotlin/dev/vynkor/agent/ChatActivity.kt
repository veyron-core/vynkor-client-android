package dev.vynkor.agent

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AiClient
import dev.vynkor.agent.agent.AiException
import dev.vynkor.agent.agent.ChatMessage
import dev.vynkor.agent.agent.ChatStore
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var profile: HostProfile
    private val history = mutableListOf<ChatMessage>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val active = ProfileStore.active(this)
        if (active == null) {
            Toast.makeText(this, R.string.no_profile, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        profile = active

        val list = findViewById<RecyclerView>(R.id.messages)
        val input = findViewById<EditText>(R.id.input)
        val send = findViewById<Button>(R.id.send)
        val clear = findViewById<Button>(R.id.clear)

        adapter = ChatAdapter()
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        history.addAll(ChatStore.load(this, profile.id))
        history.forEach { adapter.append(it) }

        send.setOnClickListener { sendMessage(input) }
        clear.setOnClickListener {
            history.clear()
            ChatStore.clear(this, profile.id)
            adapter.clear()
        }
    }

    private fun sendMessage(input: EditText) {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || busy) return
        input.setText("")

        val agent = AgentHolder.agent
        if (agent == null) {
            adapter.append(ChatMessage("error", getString(R.string.not_connected)))
            return
        }
        if (profile.aiModel.isBlank() || profile.aiApiKeyEnv.isBlank()) {
            adapter.append(ChatMessage("error", getString(R.string.ai_not_configured)))
            return
        }

        val user = ChatMessage("user", text)
        history.add(user)
        adapter.append(user)
        persist()

        busy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiClient.chat(agent, profile, history.map { it.role to it.content }) }
            }
            result.onSuccess { reply ->
                val assistant = ChatMessage("assistant", reply.content)
                history.add(assistant)
                adapter.append(assistant)
                persist()
            }.onFailure { e ->
                val message = when (e) {
                    is AiException -> e.message ?: getString(R.string.ai_error)
                    else -> e.message ?: getString(R.string.ai_error)
                }
                adapter.append(ChatMessage("error", message))
            }
            busy = false
        }
    }

    private fun persist() {
        ChatStore.save(this, profile.id, history)
    }
}
