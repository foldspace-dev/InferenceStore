package dev.mattramotar.inferencestore.core.provider

import kotlin.time.Duration

/**
 * Provider-level events for a single attempt. Core maps these into the
 * canonical store-level `InferenceEvent` lifecycle (OSS-11 / `event-model.md`).
 */
public sealed interface ProviderEvent<out Output : Any> {
    public data class Started(public val metadata: ProviderMetadata) : ProviderEvent<Nothing>
    public data class Token(public val text: String) : ProviderEvent<Nothing>
    public data class Partial<Output : Any>(public val value: Output) : ProviderEvent<Output>
    public data class Completed<Output : Any>(
        public val output: Output,
        public val rawText: String?,
        public val usage: Usage? = null,
        public val metadata: ProviderMetadata,
    ) : ProviderEvent<Output>
    public data class Failed(public val error: ProviderError) : ProviderEvent<Nothing>
}

/** Provider/model/runtime metadata surfaced on events and in the route trace. */
public data class ProviderMetadata(
    public val providerId: ProviderId,
    public val providerKind: ProviderKind,
    public val boundary: ProviderPrivacyBoundary,
    public val modelId: String? = null,
    public val modelVersion: String? = null,
    public val runtimeVersion: String? = null,
    public val capabilities: Set<Capability> = emptySet(),
    public val extra: Map<String, String> = emptyMap(),
)

/** Optional token/cost usage; fields are optional because providers differ. */
public data class Usage(
    public val inputTokens: Int? = null,
    public val outputTokens: Int? = null,
    public val totalTokens: Int? = null,
    public val estimatedCostMicros: Long? = null,
)

/**
 * A provider failure mapped to a stable [category]. Adapters MUST map raw
 * exceptions to a category; the routing mapping table itself is implemented in
 * OSS-16 (`error-fallback-mapping.md`). The raw [cause] is preserved for debug
 * hooks but must never be logged or traced with secrets.
 */
public data class ProviderError(
    public val category: ErrorCategory,
    public val message: String? = null,
    public val retryAfter: Duration? = null,
    public val cause: Throwable? = null,
)

/** Stable provider error taxonomy (canonical in `error-fallback-mapping.md`). */
public enum class ErrorCategory {
    ProviderUnavailable,
    CapabilityUnsupported,
    PolicyViolation,
    Timeout,
    RateLimited,
    TransientProviderError,
    PermanentProviderError,
    ValidationFailed,
    ParsingFailed,
    Cancelled,
    Unknown,
}
