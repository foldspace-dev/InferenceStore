package dev.mattramotar.inferencestore.provider.openai

/**
 * Configuration for an [OpenAiCompatibleProvider] (`provider-adapters.md`).
 *
 * The adapter works against any OpenAI-compatible `/chat/completions` endpoint
 * (hosted gateways or a local server). API keys are app-supplied via [apiKey] and
 * are never logged, traced, or placed in errors; the adapter provides no secret
 * store. For public mobile clients, prefer routing third-party cloud calls through
 * an app backend (see the security guide).
 */
public class OpenAiConfig(
    public val baseUrl: String,
    public val model: String,
    public val apiKey: suspend () -> String? = { null },
    public val vendor: String = "openai-compatible",
    public val organization: String? = null,
    public val supportsStructuredOutput: Boolean = false,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
    }

    internal val chatCompletionsUrl: String get() = baseUrl.removeSuffix("/") + "/chat/completions"
}
