package dev.mattramotar.inferencestore.core.monitor

import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.ProviderAttemptSummary
import dev.mattramotar.inferencestore.core.event.RequestId
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.validation.ValidationResult
import kotlin.time.Duration

/**
 * A non-suspending observer of a request's lifecycle (`observability-evals.md`).
 *
 * [MonitorEvent]s are the **redacted projection** of the canonical stream events:
 * they never carry raw prompts or outputs. Implementations must not block; hand
 * off to a non-blocking sink if they do I/O. A monitor that throws never breaks a
 * request — the engine isolates monitor failures.
 *
 * Example — count fallbacks and log failures:
 * ```kotlin
 * val store = InferenceStore.build {
 *     provider(local); provider(cloud)
 *     monitor { event ->
 *         when (event) {
 *             is MonitorEvent.FallbackStarted -> metrics.increment("fallback", event.reason.name)
 *             is MonitorEvent.RequestFailed -> logger.warn("request ${event.requestId} failed: ${event.error}")
 *             else -> {}
 *         }
 *     }
 * }
 * ```
 */
public fun interface InferenceMonitor {
    public fun onEvent(event: MonitorEvent)
}

/** Redacted lifecycle summary for [MonitorEvent.RequestCompleted] (no output content). */
public data class RequestSummary(
    public val finalProvider: String?,
    public val finalStatus: FinalStatus,
    public val tokenCount: Int,
)

/** Redacted projection of the canonical stream events (`event-model.md`). */
public sealed interface MonitorEvent {
    public val requestId: RequestId

    public data class RequestStarted(override val requestId: RequestId, public val key: InferenceKey) : MonitorEvent
    public data class RouteSelected(override val requestId: RequestId, public val provider: ProviderId) : MonitorEvent
    public data class ProviderAttemptStarted(override val requestId: RequestId, public val attempt: ProviderAttemptSummary) : MonitorEvent

    /** Cumulative token count — never the token text. */
    public data class TokenEmitted(override val requestId: RequestId, public val count: Int) : MonitorEvent
    public data class ValidationCompleted(override val requestId: RequestId, public val result: ValidationResult) : MonitorEvent
    public data class ProviderAttemptCompleted(override val requestId: RequestId, public val attempt: ProviderAttemptSummary) : MonitorEvent
    public data class FallbackStarted(override val requestId: RequestId, public val reason: FallbackReason) : MonitorEvent
    public data class RetryScheduled(
        override val requestId: RequestId,
        public val provider: ProviderId,
        public val attemptNumber: Int,
        public val delay: Duration,
    ) : MonitorEvent

    public data class RequestCompleted(override val requestId: RequestId, public val summary: RequestSummary) : MonitorEvent

    /** Carries only the stable [error] category — no message or cause. */
    public data class RequestFailed(override val requestId: RequestId, public val error: ErrorCategory) : MonitorEvent
}
