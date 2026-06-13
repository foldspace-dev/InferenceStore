package dev.mattramotar.inferencestore.core.event

import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderId
import kotlin.jvm.JvmInline

/** Correlation id for a single request execution. */
@JvmInline
public value class RequestId(public val value: String)

public enum class AttemptOutcome { Succeeded, Failed, RejectedByPolicy }

/** Summary of one provider attempt. `outcome` is null while the attempt is in flight. */
public data class ProviderAttemptSummary(
    public val provider: ProviderId,
    public val attemptNumber: Int,
    public val outcome: AttemptOutcome? = null,
    public val error: ErrorCategory? = null,
)

/**
 * Terminal result of a successful request. The route trace, validation result,
 * and cache outcome are added by OSS-11 / OSS-8 / OSS-25.
 */
public data class InferenceResult<Output : Any>(
    public val key: InferenceKey,
    public val output: Output,
    public val rawText: String? = null,
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
 * This slice carries the single-provider lifecycle. OSS-11 adds `CacheChecked`,
 * `ProvidersEvaluated`, `RouteSelected`, `ValidationCompleted`, `FallbackStarted`,
 * `RetryScheduled`, `ArtifactStored`, and the `RouteTrace` on terminal events.
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
    ) : InferenceEvent<Nothing>
}
