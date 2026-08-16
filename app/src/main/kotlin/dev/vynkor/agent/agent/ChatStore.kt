package dev.vynkor.agent.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Multi-chat persistence for the `ai` plugin's transcripts. All chats for one
 * host profile live under a single SharedPreferences entry keyed by
 * `profileId`, as a JSON array of `Chat` objects:
 *
 *   [{ id, title, created_at, updated_at, messages: [{role, content, timestamp}] }]
 *
 * Older app versions stored a bare JSON array of `{role, content}` messages
 * under the same key; `list`/`load` migrate that legacy shape into a single
 * `Chat` in place before returning.
 */
object ChatStore {
    private const val TAG = "ChatStore"
    private const val PREFS = "vynkor_chat"

    fun list(context: Context, profileId: String): List<Chat> {
        migrateLegacyIfNeeded(context, profileId)
        val chats = readAll(context, profileId) ?: return emptyList()
        return chats.sortedByDescending { it.updatedAt }
    }

    fun load(context: Context, profileId: String, chatId: String): Chat? {
        migrateLegacyIfNeeded(context, profileId)
        val chats = readAll(context, profileId) ?: return null
        return chats.firstOrNull { it.id == chatId }
    }

    fun save(context: Context, profileId: String, chat: Chat) {
        migrateLegacyIfNeeded(context, profileId)
        val chats = readAll(context, profileId).orEmpty().toMutableList()
        val idx = chats.indexOfFirst { it.id == chat.id }
        if (idx >= 0) chats[idx] = chat else chats.add(chat)
        writeAll(context, profileId, chats)
    }

    fun delete(context: Context, profileId: String, chatId: String) {
        migrateLegacyIfNeeded(context, profileId)
        val chats = readAll(context, profileId) ?: return
        val remaining = chats.filterNot { it.id == chatId }
        writeAll(context, profileId, remaining)
    }

    fun rename(context: Context, profileId: String, chatId: String, title: String) {
        migrateLegacyIfNeeded(context, profileId)
        val chats = readAll(context, profileId) ?: return
        val target = chats.firstOrNull { it.id == chatId } ?: return
        target.title = title
        writeAll(context, profileId, chats)
    }

    /** Fills a blank title from the first user message, trimmed to 40 chars. */
    fun autoTitle(chat: Chat) {
        if (chat.title.isNotBlank()) return
        val firstUser = chat.messages.firstOrNull { it.role == "user" } ?: return
        chat.title = firstUser.content.trim().take(40)
    }

    // ------------------------------------------------------------------ io

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(context: Context, profileId: String): List<Chat>? {
        val raw = prefs(context).getString(profileId, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { chatFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            // Malformed JSON: treat as absent rather than crash the UI.
            Log.w(TAG, "Failed to parse chats for profile $profileId", e)
            null
        }
    }

    private fun writeAll(context: Context, profileId: String, chats: List<Chat>) {
        val arr = JSONArray()
        chats.forEach { arr.put(chatToJson(it)) }
        prefs(context).edit().putString(profileId, arr.toString()).apply()
    }

    // ---------------------------------------------------------- (de)serialize

    private fun chatToJson(chat: Chat): JSONObject = JSONObject().apply {
        put("id", chat.id)
        put("title", chat.title)
        put("created_at", chat.createdAt)
        put("updated_at", chat.updatedAt)
        val msgs = JSONArray()
        chat.messages.forEach { m ->
            msgs.put(
                JSONObject()
                    .put("role", m.role)
                    .put("content", m.content)
                    .put("timestamp", m.timestamp),
            )
        }
        put("messages", msgs)
    }

    private fun chatFromJson(o: JSONObject): Chat {
        val messages = mutableListOf<ChatMessage>()
        val msgs = o.optJSONArray("messages")
        if (msgs != null) {
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                messages.add(
                    ChatMessage(
                        role = m.optString("role"),
                        content = m.optString("content"),
                        timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                    ),
                )
            }
        }
        return Chat(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = o.optString("title"),
            createdAt = o.optLong("created_at", System.currentTimeMillis()),
            updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
            messages = messages,
        )
    }

    // ------------------------------------------------------------- migration

    /**
     * Legacy format: a bare JSON array of `{role, content}` message objects
     * under the profileId key. Wraps it into a single `Chat` and persists the
     * new format, so `list`/`load` only ever see multi-chat data.
     */
    private fun migrateLegacyIfNeeded(context: Context, profileId: String) {
        val raw = prefs(context).getString(profileId, null) ?: return
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse legacy chats for profile $profileId", e)
            return
        }
        if (arr.length() == 0) return
        val first = arr.optJSONObject(0)
        // New-format entries always carry an `id`; message objects don't.
        if (first == null || first.has("id")) return

        val messages = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            messages.add(
                ChatMessage(
                    role = m.optString("role"),
                    content = m.optString("content"),
                ),
            )
        }
        val chat = Chat(messages = messages)
        chat.updatedAt = messages.maxOfOrNull { it.timestamp }
            ?: System.currentTimeMillis()
        autoTitle(chat)
        writeAll(context, profileId, listOf(chat))
        Log.i(TAG, "Migrated legacy transcript for profile $profileId to chat ${chat.id}")
    }
}
