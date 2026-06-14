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

/** Whether the engine may read from / write to the artifact cache for a request. */
public enum class CacheAccess { Allow, Deny }

/**
 * Per-request cache and dedupe policy (`storage-model.md` / `caching-validation-dedupe.md`).
 *
 * Privacy-by-default: [read] is allowed (a cache miss is harmless) but [write] is
 * denied — persisting a generated output is opt-in, and even when allowed the
 * engine still requires `PrivacyPolicy.persistence.persistOutput`. [allowDedupe]
 * is the in-flight request-coalescing knob (OSS-14). [ttl] /
 * [allowStaleWhileRevalidate] are hints honored by the cache implementation.
 */
public data class CachePolicy(
    public val read: CacheAccess = CacheAccess.Allow,
    public val write: CacheAccess = CacheAccess.Deny,
    public val allowDedupe: Boolean = false,
    public val ttl: Duration? = null,
    public val allowStaleWhileRevalidate: Boolean = false,
) {
    init {
        require(ttl == null || ttl > Duration.ZERO) { "ttl must be positive, was $ttl" }
    }

    public companion object {
        public val Default: CachePolicy = CachePolicy()

        /** Read and write the cache (still gated by `PrivacyPolicy.persistence.persistOutput`). */
        public fun readWrite(ttl: Duration? = null): CachePolicy =
            CachePolicy(read = CacheAccess.Allow, write = CacheAccess.Allow, ttl = ttl)
    }
}
