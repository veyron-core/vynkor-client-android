package dev.vynkor.agent.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val role: String, val content: String)

/** Persists the chat transcript per host profile (the `ai` plugin holds no
 * session state, so the full history must be re-sent every call). */
object ChatStore {
    private const val PREFS = "vynkor_chat"

    fun load(context: Context, profileId: String): List<ChatMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(profileId, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ChatMessage(o.getString("role"), o.getString("content"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, profileId: String, messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(profileId, arr.toString()).apply()
    }

    fun clear(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(profileId).apply()
    }
}
