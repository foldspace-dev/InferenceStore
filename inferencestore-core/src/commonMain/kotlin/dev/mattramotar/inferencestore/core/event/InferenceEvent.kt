package dev.mattramotar.inferencestore.core.event

import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.validation.ValidationResult
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/** Correlation id for a single request execution. */
@JvmInline
public value class RequestId(public val value: String)

@Serializable
public enum class AttemptOutcome { Succeeded, Failed, RejectedByPolicy }

/** Summary of one provider attempt. `outcome` is null while the attempt is in flight. */
public data class ProviderAttemptSummary(
    public val provider: ProviderId,
    public val attemptNumber: Int,
    public val outcome: AttemptOutcome? = null,
    public val error: ErrorCategory? = null,
)

/**
 * Terminal result of a successful request. The [trace] is populated by the
 * engine; validation result and cache outcome are added by OSS-8 / OSS-25.
 */
public data class InferenceResult<Output : Any>(
    public val key: InferenceKey,
    public val output: Output,
    public val rawText: String? = null,
    public val trace: RouteTrace? = null,
)

/** Store-level error mapped to a stable [category] (`error-fallback-mapping.md`). */
public data class InferenceError(
    public val category: ErrorCategory,
    public val message: String? = null,
    public val cause: Throwable? = null,
    public val source: ErrorSource? = null,
) {
    /**
     * Log-safe string form: [message] and [cause] are omitted (they may carry raw
     * provider content / secrets), mirroring `ProviderError`. Both remain available
     * via their properties for debug hooks; only the stable taxonomy is printed.
     */
    override fun toString(): String = "InferenceError(category=$category, source=$source)"
}

/**
 * Canonical stream events (see `event-model.md`).
 *
 * Covers the single-provider lifecycle, fallback/retry, validation, and the
 * [RouteTrace] on terminal events. The remaining routing/cache events
 * (`CacheChecked`, `ProvidersEvaluated`, `RouteSelected`, `ArtifactStored`) are
 * emitted by the features that own them (OSS-25 / OSS-19) and land with those issues.
 */
public sealed interface InferenceEvent<out Output : Any> {
    public data class Started(
        public val requestId: RequestId,
        public val key: InferenceKey,
    ) : InferenceEvent<Nothing>

    public data class ProviderAttemptStarted(
        public val requestId: RequestId,
        public val attempt: ProviderAttemptSummary,
    ) : InferenceEvent<Nothing>

    public data class Token(
        public val requestId: RequestId,
        public val text: String,
    ) : InferenceEvent<Nothing>

    public data class Partial<Output : Any>(
        public val requestId: RequestId,
        public val value: Output,
    ) : InferenceEvent<Output>

    /** Emitted after final-output validation runs for an attempt (OSS-17). */
    public data class ValidationCompleted(
        public val requestId: RequestId,
        public val result: ValidationResult,
    ) : InferenceEvent<Nothing>

    public data class ProviderAttemptCompleted(
        public val requestId: RequestId,
        public val attempt: ProviderAttemptSummary,
    ) : InferenceEvent<Nothing>

    public data class Done<Output : Any>(
        public val requestId: RequestId,
        public val result: InferenceResult<Output>,
    ) : InferenceEvent<Output>

    public data class Failed(
        public val requestId: RequestId,
        public val error: InferenceError,
        public val trace: RouteTrace? = null,
    ) : InferenceEvent<Nothing>

    /** Emitted when routing falls back to [next] (null = no further candidate). Emitted by OSS-13. */
    public data class FallbackStarted(
        public val requestId: RequestId,
        public val reason: FallbackReason,
        public val next: ProviderId?,
    ) : InferenceEvent<Nothing>

    /** Emitted when an opt-in same-provider retry is scheduled. Emitted by OSS-21. */
    public data class RetryScheduled(
        public val requestId: RequestId,
        public val provider: ProviderId,
        public val attemptNumber: Int,
        public val delay: Duration,
    ) : InferenceEvent<Nothing>
}
