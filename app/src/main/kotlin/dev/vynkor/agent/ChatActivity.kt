package dev.vynkor.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.vynkor.agent.agent.AgentHolder
import dev.vynkor.agent.agent.AgentService
import dev.vynkor.agent.agent.AiAgent
import dev.vynkor.agent.agent.AiClient
import dev.vynkor.agent.agent.AiException
import dev.vynkor.agent.agent.AiModel
import dev.vynkor.agent.agent.AiPresets
import dev.vynkor.agent.agent.Chat
import dev.vynkor.agent.agent.ChatMessage
import dev.vynkor.agent.agent.ChatStore
import dev.vynkor.agent.agent.HostProfile
import dev.vynkor.agent.agent.ProfileStore
import dev.vynkor.agent.agent.SttEngine
import dev.vynkor.agent.agent.SttRecorder
import dev.vynkor.agent.agent.SttSession
import dev.vynkor.agent.agent.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var drawerAdapter: ChatListAdapter
    private lateinit var drawer: DrawerLayout
    private var profile: HostProfile? = null
    private lateinit var chat: Chat
    private var busy = false
    private var tts: TtsEngine? = null

    private var hostModels: List<AiModel> = emptyList()
    private var hostAgents: List<AiAgent> = emptyList()

    private val recorder = SttRecorder()
    private var sttPending = false

    // Live-dictation state (offline model, emulated streaming).
    @Volatile
    private var sttSession: SttSession? = null
    private var partialJob: Job? = null
    private var sttDraftPrefix = ""

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

        profile = ProfileStore.active(this)
        chat = Chat()

        drawer = findViewById(R.id.drawer)

        // Opening the drawer (chats/settings) hides the keyboard right away.
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                ViewCompat.getWindowInsetsController(window.decorView)?.hide(WindowInsetsCompat.Type.ime())
            }
        })

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        refreshTitle()
        refreshModelChip()
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        toolbar.setOnClickListener { showModelPicker() }

        val list = findViewById<RecyclerView>(R.id.messages)
        val input = findViewById<EditText>(R.id.input)
        val send = findViewById<MaterialButton>(R.id.send)
        val mic = findViewById<MaterialButton>(R.id.mic)

        adapter = ChatAdapter(
            onUserLongPress = { message, anchor -> showUserMessageMenu(message, anchor) },
            onCopy = { copyMessage(it) },
            onMore = { message, anchor -> showAssistantMoreMenu(message, anchor) },
            onSpeak = { toggleSpeak(it) },
        )
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        adapter.submit(chat.messages)

        send.setOnClickListener { sendMessage(input) }
        mic.setOnClickListener { toggleDictation(input) }
        input.doAfterTextChanged { updateComposerButtons() }

        findViewById<Chip>(R.id.modelChip).setOnClickListener { showModelPicker() }
        findViewById<Chip>(R.id.agentChip).setOnClickListener { showAgentPicker() }

        findViewById<MaterialButton>(R.id.setUpHost).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.newChat).setOnClickListener { loadChat(null) }
        findViewById<MaterialButton>(R.id.settings).setOnClickListener {
            drawer.closeDrawers()
            startActivity(Intent(this, MainActivity::class.java))
        }

        val drawerChats = findViewById<RecyclerView>(R.id.drawerChats)
        drawerAdapter = ChatListAdapter(
            onOpen = { loadChat(it) },
            onLongPress = { showChatMenu(it) },
        )
        drawerChats.layoutManager = LinearLayoutManager(this)
        drawerChats.adapter = drawerAdapter

        updateHostState()
        refreshChatList()
        updateWelcome()
        autoConnect()

        val welcomeChips = findViewById<ChipGroup>(R.id.welcomeChips)
        listOf(
            R.string.welcome_suggest_1,
            R.string.welcome_suggest_2,
            R.string.welcome_suggest_3,
        ).forEach { res ->
            welcomeChips.addView(
                Chip(ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Chip_Suggestion)).apply {
                    text = getString(res)
                    isCheckable = false
                    setOnClickListener {
                        input.setText(text)
                        input.setSelection(input.text?.length ?: 0)
                    }
                }
            )
        }

        lifecycleScope.launch {
            AgentHolder.connectionState.collect { connected ->
                findViewById<TextView>(R.id.drawerStatusDot).setTextColor(
                    ContextCompat.getColor(
                        this@ChatActivity,
                        if (connected) R.color.connected else R.color.disconnected,
                    )
                )
                findViewById<TextView>(R.id.drawerStatus).setText(
                    if (connected) R.string.status_connected else R.string.status_disconnected
                )
                if (connected) refreshHostAi()
            }
        }

        val engine = TtsEngine(this)
        engine.onDone = {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideTtsPill()
                adapter.setSpeaking(null)
            }
        }
        tts = engine
        findViewById<ImageButton>(R.id.ttsStop).setOnClickListener { stopSpeaking() }
    }

    override fun onResume() {
        super.onResume()
        val current = ProfileStore.active(this)
        if (current?.id != profile?.id) {
            profile = current
            loadChat(null)
        }
        refreshModelChip()
        refreshAgentChip()
        updateHostState()
        refreshChatList()
        updateWelcome()
        autoConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        partialJob?.cancel()
        partialJob = null
        sttSession = null
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

    private fun refreshModelChip() {
        val active = profile
        val model = active?.effectiveModel()?.takeIf { it.isNotBlank() }
            ?: hostModels.firstOrNull { it.isDefault }?.id
            ?: ""
        findViewById<Chip>(R.id.modelChip).text = model
        findViewById<Chip>(R.id.modelChip).visibility =
            if (model.isBlank()) View.GONE else View.VISIBLE
    }

    private fun refreshAgentChip() {
        val chip = findViewById<Chip>(R.id.agentChip)
        if (hostAgents.isEmpty()) {
            chip.visibility = View.GONE
            return
        }
        val agentId = profile?.aiAgent.orEmpty()
        val name = hostAgents.firstOrNull { it.id == agentId }?.name
            ?: hostAgents.firstOrNull { it.isDefault }?.name
            ?: agentId.ifBlank { getString(R.string.agent_fallback) }
        chip.text = name
        chip.visibility = View.VISIBLE
    }

    /** Pull the host's model/agent lists (list_models/list_agents). */
    private fun refreshHostAi() {
        val agent = AgentHolder.agent ?: return
        lifecycleScope.launch {
            val (models, agents) = withContext(Dispatchers.IO) {
                runCatching { AiClient.listModels(agent) to AiClient.listAgents(agent) }
                    .getOrDefault(emptyList<AiModel>() to emptyList<AiAgent>())
            }
            if (models.isNotEmpty()) hostModels = models
            if (agents.isNotEmpty()) hostAgents = agents
            refreshModelChip()
            refreshAgentChip()
        }
    }

    private fun updateHostState() {
        val hasProfile = profile != null
        findViewById<LinearLayout>(R.id.emptyState).visibility =
            if (hasProfile) View.GONE else View.VISIBLE
        findViewById<View>(R.id.composerCard).visibility =
            if (hasProfile) View.VISIBLE else View.GONE
        if (!hasProfile) {
            findViewById<TextView>(R.id.typing).visibility = View.GONE
            findViewById<TextView>(R.id.listening).visibility = View.GONE
        }
    }

    private fun refreshChatList() {
        val active = profile
        findViewById<TextView>(R.id.drawerProfileName).text =
            if (active == null) getString(R.string.no_profile)
            else active.name.ifBlank { getString(R.string.unnamed_profile) }
        drawerAdapter.submit(
            if (active == null) emptyList() else ChatStore.list(this, active.id)
        )
    }

    private fun updateWelcome() {
        val show = profile != null && chat.messages.isEmpty()
        findViewById<View>(R.id.welcomeState).visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Tries to reach the last active host as soon as the app opens. */
    private fun autoConnect() {
        if (profile != null && AgentHolder.agent == null) {
            requestPermissions()
            AgentService.start(this)
        }
    }

    private fun requestPermissions() {
        val missing = MainActivity.PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 42)
        }
    }

    private fun loadChat(loaded: Chat?) {
        chat = loaded ?: Chat()
        adapter.submit(chat.messages)
        refreshTitle()
        updateWelcome()
        drawer.closeDrawers()
    }

    private fun showChatMenu(target: Chat) {
        val options = arrayOf(
            getString(R.string.rename_chat),
            getString(R.string.delete_chat),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(target.title.ifBlank { getString(R.string.new_chat) })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameChat(target)
                    1 -> confirmDelete(target)
                }
            }
            .show()
    }

    private fun renameChat(target: Chat) {
        val input = EditText(this)
        input.hint = getString(R.string.rename_chat_hint)
        input.setText(target.title)
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
                profile?.let { ChatStore.rename(this, it.id, target.id, title) }
                if (target.id == chat.id) {
                    chat.title = title
                    refreshTitle()
                }
                refreshChatList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(target: Chat) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_chat)
            .setMessage(R.string.delete_chat_confirm)
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                profile?.let { ChatStore.delete(this, it.id, target.id) }
                if (target.id == chat.id) {
                    loadChat(null)
                }
                refreshChatList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ send

    private fun sendMessage(input: EditText) {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || busy) return
        input.setText("")

        val active = profile ?: return
        val agent = AgentHolder.agent
        if (agent == null) {
            appendMessage(ChatMessage("error", getString(R.string.not_connected)))
            return
        }
        val useAgent = active.aiAgent.isNotBlank()
        if (!useAgent && (active.effectiveModel().isBlank() || active.aiApiKeyEnv.isBlank())) {
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
                runCatching { AiClient.chat(agent, active, chat.messages.map { it.role to it.content }) }
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
        profile?.let { ChatStore.save(this, it.id, chat) }
        updateWelcome()
        refreshChatList()
    }

    private fun setBusyUi(b: Boolean) {
        findViewById<MaterialButton>(R.id.send).isEnabled = !b
        findViewById<TextView>(R.id.typing).visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun updateComposerButtons() {
        val hasText = findViewById<EditText>(R.id.input).text?.isNotBlank() == true
        val dictating = recorder.isRecording() || sttPending
        findViewById<MaterialButton>(R.id.send).visibility =
            if (hasText && !dictating) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.mic).visibility =
            if (dictating || !hasText) View.VISIBLE else View.GONE
    }

    // ----------------------------------------------------- model switcher

    private fun showModelPicker() {
        val active = profile ?: return
        val models = hostModels.map { it.id }
            .ifEmpty { AiPresets.modelsFor(active.aiProvider) }
            .ifEmpty { listOf(active.effectiveModel()) }
        val labels = models + getString(R.string.custom_model)
        val current = active.effectiveModel()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_picker_title)
            .setSingleChoiceItems(labels.toTypedArray(), models.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                if (which < models.size) {
                    saveModel(models[which])
                } else {
                    customModelDialog()
                }
            }
            .show()
    }

    // ----------------------------------------------------- agent switcher

    private fun showAgentPicker() {
        if (hostAgents.isEmpty()) return
        val names = hostAgents.map { it.name }
        val current = hostAgents.indexOfFirst { it.id == profile?.aiAgent }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_picker_title)
            .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                saveAgent(hostAgents[which].id)
            }
            .show()
    }

    private fun saveAgent(agentId: String) {
        val active = profile ?: return
        profile = active.copy(aiAgent = agentId)
        ProfileStore.save(this, profile!!)
        refreshAgentChip()
        Toast.makeText(this, getString(R.string.agent_switched, agentName(agentId)), Toast.LENGTH_SHORT).show()
    }

    private fun agentName(agentId: String): String =
        hostAgents.firstOrNull { it.id == agentId }?.name ?: agentId

    private fun customModelDialog() {
        val active = profile ?: return
        val input = EditText(this)
        input.hint = getString(R.string.ai_model_hint)
        input.setText(active.aiModel)
        input.setSelection(input.text.length)
        val holder = FrameLayout(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        holder.setPadding(pad, pad, pad, 0)
        holder.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_model)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveModel(input.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveModel(modelId: String) {
        if (modelId.isBlank()) return
        val active = profile ?: return
        profile = active.copy(aiModel = modelId)
        ProfileStore.save(this, profile!!)
        refreshModelChip()
        Toast.makeText(this, getString(R.string.model_switched, modelId), Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------- message actions

    private fun showUserMessageMenu(message: ChatMessage, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.copy_message)
        popup.menu.add(0, 2, 1, R.string.speak_message)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    copyMessage(message)
                    true
                }
                2 -> {
                    toggleSpeak(message)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAssistantMoreMenu(message: ChatMessage, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.message_fork)
        // Future plugin actions — reserved, disabled for now.
        popup.menu.add(0, 2, 1, R.string.message_gmail_draft).isEnabled = false
        popup.menu.add(0, 3, 2, R.string.message_export_docs).isEnabled = false
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                forkBranchAt(message)
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun forkBranchAt(message: ChatMessage) {
        val active = profile ?: return
        val idx = chat.messages.indexOfFirst {
            it.timestamp == message.timestamp &&
                it.role == message.role &&
                it.content == message.content
        }
        if (idx < 0) return
        val branch = Chat()
        branch.messages.addAll(chat.messages.subList(0, idx + 1))
        ChatStore.autoTitle(branch)
        ChatStore.save(this, active.id, branch)
        loadChat(branch)
        Toast.makeText(this, R.string.fork_created, Toast.LENGTH_SHORT).show()
    }

    private fun copyMessage(message: ChatMessage) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_message), message.content))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun toggleSpeak(message: ChatMessage) {
        val engine = tts ?: return
        if (engine.isSpeaking()) {
            stopSpeaking()
        } else {
            engine.speak(message.content)
            findViewById<View>(R.id.ttsPill).visibility = View.VISIBLE
            adapter.setSpeaking(message)
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
        hideTtsPill()
        adapter.setSpeaking(null)
    }

    private fun hideTtsPill() {
        findViewById<View>(R.id.ttsPill).visibility = View.GONE
    }

    // ------------------------------------------------------------- dictation

    private fun toggleDictation(input: EditText) {
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
        val session = SttEngine.get(this).newSession()
        if (session == null) {
            sttPending = false
            setListeningUi(false)
            Toast.makeText(this, R.string.stt_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        sttSession = session
        val input = findViewById<EditText>(R.id.input)
        val editable = input.text?.toString().orEmpty()
        sttDraftPrefix = editable.substring(0, input.selectionStart.coerceIn(0, editable.length))
        recorder.start(onChunk = ::onSttChunk)
        if (!recorder.isRecording()) {
            sttSession = null
            sttPending = false
            setListeningUi(false)
            Toast.makeText(this, R.string.mic_start_failed, Toast.LENGTH_SHORT).show()
            return
        }
        partialJob = lifecycleScope.launch {
            while (isActive && sttSession != null) {
                delay(PARTIAL_INTERVAL_MS)
                val current = sttSession ?: break
                val text = withContext(Dispatchers.IO) {
                    SttEngine.get(this@ChatActivity).partial(current)
                }
                if (text.isNotBlank()) {
                    updateDraft(text)
                }
            }
        }
    }

    /** Recorder thread: accumulate audio only — decoding happens on IO. */
    private fun onSttChunk(chunk: FloatArray) {
        val session = sttSession ?: return
        SttEngine.get(this).feed(session, chunk)
    }

    private fun stopDictation(input: EditText) {
        sttPending = false
        partialJob?.cancel()
        partialJob = null
        val session = sttSession
        sttSession = null
        recorder.stop()
        setListeningUi(false)
        if (session != null) {
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    SttEngine.get(this@ChatActivity).finish(session)
                }
                if (text.isNotBlank()) {
                    updateDraft(text)
                }
            }
        }
    }

    /** Live draft: prefix (text before the cursor at dictation start) + partial. */
    private fun updateDraft(text: String) {
        val input = findViewById<EditText>(R.id.input)
        input.setText(sttDraftPrefix + text)
        input.setSelection(input.text?.length ?: 0)
    }

    private fun setListeningUi(listening: Boolean) {
        findViewById<TextView>(R.id.listening).visibility = if (listening) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.mic).setIconTintResource(
            if (listening) R.color.error else R.color.primary,
        )
        updateComposerButtons()
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"

        /** How often the offline recognizer re-decodes the accumulated audio. */
        private const val PARTIAL_INTERVAL_MS = 1000L
    }
}
