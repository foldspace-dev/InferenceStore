package dev.mattramotar.inferencestore.core.policy

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// PrivacyPolicy / PrivacyClass and the privacy enforcement gate live in Privacy.kt (OSS-15).

/**
 * Timeout layers for a request (`timeout-retry-policy.md`).
 *
 * The engine enforces [requestTimeout] (terminal `RequestDeadlineExceeded`),
 * [attemptTimeout] (per attempt; may fall back), and [availabilityTimeout] (per
 * availability probe). [timeToFirstTokenTimeout] / [idleStreamTimeout] /
 * [validationTimeout] are part of the contract and land with streaming guardrails.
 */
public data class TimeoutPolicy(
    public val requestTimeout: Duration? = null,
    public val availabilityTimeout: Duration? = 500.milliseconds,
    public val attemptTimeout: Duration? = null,
    public val timeToFirstTokenTimeout: Duration? = null,
    public val idleStreamTimeout: Duration? = null,
    public val validationTimeout: Duration? = null,
) {
    init {
        for ((name, value) in listOf(
            "requestTimeout" to requestTimeout,
            "availabilityTimeout" to availabilityTimeout,
            "attemptTimeout" to attemptTimeout,
            "timeToFirstTokenTimeout" to timeToFirstTokenTimeout,
            "idleStreamTimeout" to idleStreamTimeout,
            "validationTimeout" to validationTimeout,
        )) {
            require(value == null || value > Duration.ZERO) { "$name must be positive, was $value" }
        }
    }

    public companion object {
        public val Default: TimeoutPolicy = TimeoutPolicy()
    }
}

/**
 * Same-provider retry config (`timeout-retry-policy.md`). Disabled by default —
 * core prefers fallback over hidden retries; when enabled each retry is an
 * explicit `RetryScheduled` event and trace entry.
 */
public data class RetryPolicy(
    public val maxRetriesPerAttempt: Int = 0,
    public val retryableCategories: Set<ErrorCategory> = emptySet(),
    public val backoff: BackoffPolicy = BackoffPolicy.None,
    public val respectRetryAfter: Boolean = true,
) {
    init {
        require(maxRetriesPerAttempt >= 0) {
            "maxRetriesPerAttempt must be non-negative, was $maxRetriesPerAttempt"
        }
    }

    public companion object {
        public val Default: RetryPolicy = RetryPolicy()
    }
}

/**
 * Cache policy placeholder. OSS-25 defines the artifact/cache interfaces and
 * OSS-11 wires dedupe; [allowDedupe] is opt-in per `threading-dispatchers.md`.
 */
public data class CachePolicy(
    public val allowDedupe: Boolean = false,
) {
    public companion object {
        public val Default: CachePolicy = CachePolicy()
    }
}
