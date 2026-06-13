package dev.mattramotar.inferencestore.core.event

import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderId
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

/** Store-level error; the full taxonomy and routing mapping live in OSS-16. */
public data class InferenceError(
    public val category: ErrorCategory,
    public val message: String? = null,
    public val cause: Throwable? = null,
)

/**
 * Canonical stream events (see `event-model.md`).
 *
 * Covers the single-provider lifecycle plus the fallback/retry events and the
 * [RouteTrace] on terminal events. The routing/cache/validation events
 * (`CacheChecked`, `ProvidersEvaluated`, `RouteSelected`, `ValidationCompleted`,
 * `ArtifactStored`) are emitted by the features that own them (OSS-13 / OSS-25 /
 * OSS-8) and land with those issues.
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
