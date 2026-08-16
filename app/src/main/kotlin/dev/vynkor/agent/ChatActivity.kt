package dev.vynkor.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AiClient
import dev.vynkor.agent.agent.AiException
import dev.vynkor.agent.agent.Chat
import dev.vynkor.agent.agent.ChatMessage
import dev.vynkor.agent.agent.ChatStore
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.agent.SttEngine
import dev.vynkor.agent.agent.SttRecorder
import dev.vynkor.agent.agent.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var profile: HostProfile
    private lateinit var chat: Chat
    private var busy = false
    private var tts: TtsEngine? = null

    private val recorder = SttRecorder()
    private var sttPending = false

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startDictation()
            } else {
                Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

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

        chat = intent.getStringExtra(EXTRA_CHAT_ID)
            ?.let { ChatStore.load(this, profile.id, it) }
            ?: Chat()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        refreshTitle()
        toolbar.setNavigationOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.messages)
        val input = findViewById<TextInputEditText>(R.id.input)
        val send = findViewById<MaterialButton>(R.id.send)
        val mic = findViewById<MaterialButton>(R.id.mic)

        adapter = ChatAdapter(
            onCopy = { copyMessage(it) },
            onSpeak = { toggleSpeak(it) },
        )
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        adapter.submit(chat.messages)

        send.setOnClickListener { sendMessage(input) }
        mic.setOnClickListener { toggleDictation(input) }

        tts = TtsEngine(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recorder.isRecording()) {
            recorder.stop()
        }
        sttPending = false
        tts?.shutdown()
        tts = null
    }

    private fun refreshTitle() {
        findViewById<MaterialToolbar>(R.id.toolbar).title =
            chat.title.ifBlank { getString(R.string.new_chat) }
    }

    private fun sendMessage(input: TextInputEditText) {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || busy) return
        input.setText("")

        val agent = AgentHolder.agent
        if (agent == null) {
            appendMessage(ChatMessage("error", getString(R.string.not_connected)))
            return
        }
        if (profile.effectiveModel().isBlank() || profile.aiApiKeyEnv.isBlank()) {
            appendMessage(ChatMessage("error", getString(R.string.ai_not_configured)))
            return
        }

        appendMessage(ChatMessage("user", text))
        if (chat.title.isBlank()) {
            ChatStore.autoTitle(chat)
            refreshTitle()
        }

        busy = true
        setBusyUi(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiClient.chat(agent, profile, chat.messages.map { it.role to it.content }) }
            }
            result.onSuccess { reply ->
                appendMessage(ChatMessage("assistant", reply.content))
            }.onFailure { e ->
                val message = when (e) {
                    is AiException -> e.message ?: getString(R.string.ai_error)
                    else -> e.message ?: getString(R.string.ai_error)
                }
                appendMessage(ChatMessage("error", message))
            }
            busy = false
            setBusyUi(false)
        }
    }

    private fun appendMessage(message: ChatMessage) {
        chat.messages.add(message)
        adapter.append(message)
        chat.updatedAt = System.currentTimeMillis()
        ChatStore.save(this, profile.id, chat)
    }

    private fun setBusyUi(b: Boolean) {
        findViewById<MaterialButton>(R.id.send).isEnabled = !b
        findViewById<TextView>(R.id.typing).visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun copyMessage(message: ChatMessage) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_message), message.content))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun toggleSpeak(message: ChatMessage) {
        val engine = tts ?: return
        if (engine.isSpeaking()) {
            engine.stop()
        } else {
            engine.speak(message.content)
        }
        adapter.setSpeaking(if (engine.isSpeaking()) message else null)
    }

    private fun toggleDictation(input: TextInputEditText) {
        if (recorder.isRecording()) {
            stopDictation(input)
            return
        }
        if (sttPending) {
            sttPending = false
            setListeningUi(false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startDictation()
    }

    private fun startDictation() {
        sttPending = true
        setListeningUi(true)
        val engine = SttEngine.get(this)
        if (engine.isReady()) {
            beginRecording()
            return
        }
        engine.ensureLoaded {
            // loader thread — hop back to the main thread for UI/recording
            runOnUiThread {
                if (isDestroyed || !sttPending) return@runOnUiThread
                if (SttEngine.get(this).isReady()) {
                    beginRecording()
                } else {
                    sttPending = false
                    setListeningUi(false)
                    Toast.makeText(this, R.string.stt_not_ready, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun beginRecording() {
        recorder.start()
        if (!recorder.isRecording()) {
            sttPending = false
            setListeningUi(false)
            Toast.makeText(this, R.string.mic_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopDictation(input: TextInputEditText) {
        sttPending = false
        val samples = recorder.stop()
        setListeningUi(false)
        if (samples.isEmpty()) return
        val engine = SttEngine.get(this)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { engine.transcribe(samples) }
            if (text.isNotBlank()) {
                insertTranscript(input, text)
            }
        }
    }

    private fun insertTranscript(input: TextInputEditText, text: String) {
        val editable = input.text ?: return
        val selStart = input.selectionStart.coerceIn(0, editable.length)
        val selEnd = input.selectionEnd.coerceIn(selStart, editable.length)
        val insert = if (selStart > 0 && editable[selStart - 1] != ' ' && !text.startsWith(' ')) {
            " $text"
        } else {
            text
        }
        editable.replace(selStart, selEnd, insert.trim())
    }

    private fun setListeningUi(listening: Boolean) {
        findViewById<TextView>(R.id.listening).visibility = if (listening) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.mic).setIconTintResource(
            if (listening) R.color.error else R.color.primary,
        )
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
    }
}
