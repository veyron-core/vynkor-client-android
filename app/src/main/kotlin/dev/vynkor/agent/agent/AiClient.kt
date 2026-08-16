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

data class AiModel(
    val id: String,
    val provider: String,
    val baseUrl: String,
    val apiKeyEnv: String,
    val isDefault: Boolean,
)

data class AiAgent(
    val id: String,
    val name: String,
    val modelId: String,
    val systemPrompt: String,
    val goal: String,
    val description: String,
    val isDefault: Boolean,
)

/**
 * Thin wrapper over the host `ai` plugin. The plugin keeps its own database
 * of declared + auto-discovered models and agent profiles (host-side), so
 * the app asks the host for the list (`list_models`/`list_agents`) instead
 * of carrying provider/base_url/api_key_env itself. `chat_completion` names
 * either an agent (`agent_id`) or a model id; the host resolves the
 * endpoint and the API key (which never travels).
 */
object AiClient {
    private const val TIMEOUT_MS = 30_000u

    fun chat(agent: Agent, profile: HostProfile, messages: List<Pair<String, String>>): AiReply {
        val msgs = JSONArray()
        messages.forEach { (role, content) ->
            msgs.put(JSONObject().put("role", role).put("content", content))
        }
        val params = JSONObject().apply {
            val agentId = profile.aiAgent
            if (agentId.isNotBlank()) {
                put("agent_id", agentId)
            } else {
                put("provider", profile.aiProvider)
                if (profile.effectiveBaseUrl().isNotBlank()) put("base_url", profile.effectiveBaseUrl())
                put("model", profile.effectiveModel())
                put("api_key_env", profile.aiApiKeyEnv)
            }
            put("messages", msgs)
            put("max_tokens", 1024)
            put("timeout_ms", 30_000)
        }
        val data = request(agent, "chat_completion", params)
        val usage = data.optJSONObject("usage")
        return AiReply(
            content = data.optString("content"),
            stopReason = data.optString("stop_reason"),
            inputTokens = usage?.optLong("input_tokens") ?: 0L,
            outputTokens = usage?.optLong("output_tokens") ?: 0L,
        )
    }

    /** Models the host can complete with (declared + auto-discovered). */
    fun listModels(agent: Agent): List<AiModel> {
        val arr = requestArray(agent, "list_models")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    AiModel(
                        id = o.optString("id"),
                        provider = o.optString("provider"),
                        baseUrl = o.optString("base_url"),
                        apiKeyEnv = o.optString("api_key_env"),
                        isDefault = o.optBoolean("is_default"),
                    )
                )
            }
        }
    }

    /** Agent profiles defined on the host. */
    fun listAgents(agent: Agent): List<AiAgent> {
        val arr = requestArray(agent, "list_agents")
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    AiAgent(
                        id = o.optString("id"),
                        name = o.optString("name").ifBlank { o.optString("id") },
                        modelId = o.optString("model_id"),
                        systemPrompt = o.optString("system_prompt"),
                        goal = o.optString("goal"),
                        description = o.optString("description"),
                        isDefault = o.optBoolean("is_default"),
                    )
                )
            }
        }
    }

    private fun requestArray(agent: Agent, action: String): JSONArray =
        JSONArray(requestRaw(agent, action, JSONObject()))

    private fun request(agent: Agent, action: String, params: JSONObject): JSONObject =
        JSONObject(requestRaw(agent, action, params))

    private fun requestRaw(agent: Agent, action: String, params: JSONObject): String {
        val reply = agent.request(
            "kernel",
            action,
            params.toString().toByteArray(Charsets.UTF_8),
            TIMEOUT_MS,
        )
        if (reply.status != ActionReplyStatus.OK) {
            val detail = reply.error.ifBlank { reply.status.name }
            throw AiException(detail)
        }
        return String(reply.dataJson, Charsets.UTF_8)
    }
}