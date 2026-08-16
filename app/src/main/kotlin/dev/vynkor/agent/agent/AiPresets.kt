package dev.vynkor.agent.agent

/**
 * Curated AI-plugin presets for the per-host profile form and the in-chat
 * model switcher. Each preset matches a common host deployment of the `ai`
 * plugin: provider id, OpenAI-compatible base URL, the host-side API-key env
 * var name, and a sensible model list.
 */
object AiPresets {

    data class Preset(
        val label: String,
        val provider: String,
        val baseUrl: String,
        val apiKeyEnv: String,
        val defaultModel: String,
        val models: List<String>,
    )

    val PRESETS = listOf(
        Preset(
            label = "Ollama",
            provider = "openai",
            baseUrl = "http://localhost:11434/v1",
            apiKeyEnv = "OLLAMA_API_KEY",
            defaultModel = "llama3.2",
            models = listOf(
                "llama3.2", "llama3.1", "llama3.3", "qwen2.5", "qwen2.5-coder",
                "deepseek-r1", "mistral", "phi3",
            ),
        ),
        Preset(
            label = "OpenAI",
            provider = "openai",
            baseUrl = "https://api.openai.com/v1",
            apiKeyEnv = "OPENAI_API_KEY",
            defaultModel = "gpt-4o-mini",
            models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano", "o4-mini"),
        ),
        Preset(
            label = "Anthropic",
            provider = "anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            apiKeyEnv = "ANTHROPIC_API_KEY",
            defaultModel = "claude-sonnet-4-5",
            models = listOf(
                "claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-5",
                "claude-sonnet-5", "claude-opus-5",
            ),
        ),
        Preset(
            label = "Groq",
            provider = "openai",
            baseUrl = "https://api.groq.com/openai/v1",
            apiKeyEnv = "GROQ_API_KEY",
            defaultModel = "llama-3.3-70b-versatile",
            models = listOf(
                "llama-3.3-70b-versatile", "llama-3.1-8b-instant",
                "gemma2-9b-it", "mixtral-8x7b-32768",
            ),
        ),
        Preset(
            label = "LM Studio",
            provider = "openai",
            baseUrl = "http://localhost:1234/v1",
            apiKeyEnv = "LMSTUDIO_API_KEY",
            defaultModel = "local-model",
            models = listOf("local-model"),
        ),
    )

    /** Providers the profile form offers in its dropdown. */
    val PROVIDERS = listOf("openai", "anthropic")

    fun byLabel(label: String): Preset? = PRESETS.firstOrNull { it.label == label }

    /** Suggested model ids for a provider, deduplicated across presets. */
    fun modelsFor(provider: String): List<String> =
        PRESETS.filter { it.provider == provider }
            .flatMap { it.models }
            .distinct()
}
