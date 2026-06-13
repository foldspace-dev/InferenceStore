package dev.mattramotar.inferencestore.core.event

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.serialization.Serializable

/**
 * Why a request fell back from one provider to another (canonical in
 * `event-model.md`). Adapters map a raw failure to an [ErrorCategory]; OSS-16
 * maps that category to a [FallbackReason].
 */
@Serializable
public enum class FallbackReason {
    ProviderUnavailable,
    CapabilityUnsupported,
    PolicyViolation,
    Timeout,
    RateLimited,
    TransientError,
    PermanentError,
    ValidatorRejected,
    SchemaInvalid,
    OutputParserFailed,
    Unknown,
}

/** Terminal disposition of a routed request. */
@Serializable
public enum class FinalStatus { Succeeded, Failed, Cancelled, PrivacyDenied }

/**
 * Durable, redacted summary of a request's routing lifecycle
 * (`event-model.md` / `observability-evals.md`).
 *
 * Designed to be serialized and persisted safely: it holds IDs, categories, and
 * timings — never raw prompts or outputs. Timings are epoch milliseconds and are
 * null when not measured. Carried on [InferenceResult] (success) and
 * [InferenceEvent.Failed].
 */
@Serializable
public data class RouteTrace(
    public val requestId: String,
    public val key: String,
    public val finalStatus: FinalStatus,
    public val policyId: String? = null,
    public val attempts: List<ProviderAttemptTrace> = emptyList(),
    public val rejectedProviders: List<RejectedProviderTrace> = emptyList(),
    /**
     * Why routing fell back, aligned with [attempts]: index `i` is the reason
     * routing fell back from `attempts[i]` to `attempts[i + 1]`.
     */
    public val fallbackReasons: List<FallbackReason> = emptyList(),
    public val finalProvider: String? = null,
    public val startedAtMillis: Long? = null,
    public val completedAtMillis: Long? = null,
)

/** One invoked provider attempt within a [RouteTrace]. */
@Serializable
public data class ProviderAttemptTrace(
    public val providerId: String,
    public val providerKind: ProviderKind,
    /** Null while the attempt is in flight or was interrupted (e.g. cancellation). */
    public val outcome: AttemptOutcome? = null,
    public val modelId: String? = null,
    public val errorCategory: ErrorCategory? = null,
    public val firstTokenAtMillis: Long? = null,
    public val completedAtMillis: Long? = null,
)

/**
 * A provider that was considered but never invoked — e.g. denied by the privacy
 * policy or lacking a required capability. Recorded so privacy denials are
 * auditable in the trace without any provider call.
 */
@Serializable
public data class RejectedProviderTrace(
    public val providerId: String,
    public val reason: FallbackReason,
)
