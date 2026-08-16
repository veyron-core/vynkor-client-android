package dev.vynkor.agent.agent

import dev.vynkor.agent.ActionReplyStatus
import dev.vynkor.agent.Agent
import org.json.JSONArray
import org.json.JSONObject

class AiException(message: String) : Exception(message)

data class AiReply(
    val content: String,
    val stopReason: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

/**
 * Thin wrapper over the host `ai` plugin's `chat_completion` action. Builds the
 * params JSON from the profile's AI fields + the transcript, calls the generic
 * `Agent.request`, and unwraps the normalized response. The API key never
 * travels in the payload — only its env-var name (`api_key_env`).
 */
object AiClient {
    private const val TIMEOUT_MS = 30_000u

    fun chat(agent: Agent, profile: HostProfile, messages: List<Pair<String, String>>): AiReply {
        val msgs = JSONArray()
        messages.forEach { (role, content) ->
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val params = JSONObject().apply {
            put("provider", profile.aiProvider)
            if (profile.aiBaseUrl.isNotBlank()) put("base_url", profile.aiBaseUrl)
            put("model", profile.aiModel)
            put("api_key_env", profile.aiApiKeyEnv)
            put("messages", msgs)
            put("max_tokens", 1024)
            put("timeout_ms", 30_000)
        }
        val reply = agent.request(
            "kernel",
            "chat_completion",
            params.toString().toByteArray(Charsets.UTF_8),
            TIMEOUT_MS,
        )
        if (reply.status != ActionReplyStatus.OK) {
            val detail = reply.error.ifBlank { reply.status.name }
            throw AiException(detail)
        }
        val data = JSONObject(String(reply.dataJson, Charsets.UTF_8))
        val usage = data.optJSONObject("usage")
        return AiReply(
            content = data.optString("content"),
            stopReason = data.optString("stop_reason"),
            inputTokens = usage?.optLong("input_tokens") ?: 0L,
            outputTokens = usage?.optLong("output_tokens") ?: 0L,
        )
    }
}
