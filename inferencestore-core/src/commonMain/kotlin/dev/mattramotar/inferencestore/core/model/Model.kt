package dev.mattramotar.inferencestore.core.model

import dev.mattramotar.inferencestore.core.policy.CachePolicy
import dev.mattramotar.inferencestore.core.policy.FallbackPolicy
import dev.mattramotar.inferencestore.core.policy.InferencePolicy
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.policy.RetryPolicy
import dev.mattramotar.inferencestore.core.policy.TimeoutPolicy
import kotlinx.serialization.KSerializer

/**
 * Stable identity for an inference request.
 *
 * A key is REQUIRED for durable cache, dedupe, artifact, and trace semantics
 * (see `api-design.md`; consumed by OSS-20 fingerprinting). For one-off calls
 * that must not participate in caching or dedupe, create an "ephemeral" key
 * with a caller-unique [id] and disable caching on the request — such keys are
 * not meant to be reused.
 */
public data class InferenceKey(
    public val namespace: String,
    public val id: String,
    public val version: String? = null,
) {
    /**
     * Stable, collision-free rendering used as a fingerprint input. Delimiters
     * (`\`, `/`, `@`) in each component are escaped so that distinct keys can
     * never collapse to the same string (e.g. `("a/b","c")` vs `("a","b/c")`).
     */
    public fun asString(): String {
        fun esc(s: String): String =
            s.replace("\\", "\\\\").replace("/", "\\/").replace("@", "\\@")
        return buildString {
            append(esc(namespace))
            append('/')
            append(esc(id))
            if (version != null) {
                append('@')
                append(esc(version))
            }
        }
    }
}

/** Request input. MVP supports plain text and chat messages; multimodal is post-MVP. */
public sealed interface InferenceInput {
    public data class Text(public val value: String) : InferenceInput
    public data class Messages(public val messages: List<ChatMessage>) : InferenceInput
}

public data class ChatMessage(
    public val role: ChatRole,
    public val content: String,
)

public enum class ChatRole { System, User, Assistant, Tool }

/**
 * Declares the typed output of a request. The output type travels on the
 * request, not on a generic store (RFC-0001).
 *
 * Validators and JSON schema are added by OSS-17 / OSS-23; this slice covers
 * plain text, serializer-based JSON, and a custom parser.
 */
public sealed interface OutputSpec<Output : Any> {
    public data object Text : OutputSpec<String>
    public data class Json<Output : Any>(public val serializer: KSerializer<Output>) : OutputSpec<Output>
    public data class Custom<Output : Any>(public val parser: OutputParser<Output>) : OutputSpec<Output>
}

/** Parses provider raw text into a typed [Output]. */
public fun interface OutputParser<Output : Any> {
    public fun parse(rawText: String): Output
}

/** Optional prompt template/version metadata; a full prompt registry is post-MVP. */
public data class PromptSpec(
    public val template: String? = null,
    public val version: String? = null,
)

/**
 * A single inference request.
 *
 * Policy, cache, timeout, retry, and privacy default to their canonical
 * defaults; their full behavior is implemented in OSS-13 / OSS-25 / OSS-18 /
 * OSS-15 respectively.
 */
public data class InferenceRequest<Output : Any>(
    public val key: InferenceKey,
    public val input: InferenceInput,
    public val output: OutputSpec<Output>,
    public val policy: InferencePolicy? = null,
    public val privacy: PrivacyPolicy = PrivacyPolicy.Default,
    public val cache: CachePolicy = CachePolicy.Default,
    public val timeout: TimeoutPolicy = TimeoutPolicy.Default,
    public val retry: RetryPolicy = RetryPolicy.Default,
    public val fallback: FallbackPolicy = FallbackPolicy.Default,
    public val prompt: PromptSpec? = null,
    public val metadata: Map<String, String> = emptyMap(),
) {
    public companion object {
        /** Convenience for a plain-text-in, text-out request. */
        public fun text(
            key: InferenceKey,
            input: String,
            privacy: PrivacyPolicy = PrivacyPolicy.Default,
            policy: InferencePolicy? = null,
            fallback: FallbackPolicy = FallbackPolicy.Default,
        ): InferenceRequest<String> = InferenceRequest(
            key = key,
            input = InferenceInput.Text(input),
            output = OutputSpec.Text,
            privacy = privacy,
            policy = policy,
            fallback = fallback,
        )

        /** Convenience for a text-in, typed-JSON-out request. */
        public fun <Output : Any> json(
            key: InferenceKey,
            input: String,
            serializer: KSerializer<Output>,
            privacy: PrivacyPolicy = PrivacyPolicy.Default,
            policy: InferencePolicy? = null,
            fallback: FallbackPolicy = FallbackPolicy.Default,
        ): InferenceRequest<Output> = InferenceRequest(
            key = key,
            input = InferenceInput.Text(input),
            output = OutputSpec.Json(serializer),
            privacy = privacy,
            policy = policy,
            fallback = fallback,
        )
    }
}
