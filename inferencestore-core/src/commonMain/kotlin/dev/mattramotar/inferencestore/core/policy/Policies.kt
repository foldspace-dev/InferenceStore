package dev.mattramotar.inferencestore.core.policy

import kotlin.time.Duration

// PrivacyPolicy / PrivacyClass and the privacy enforcement gate live in Privacy.kt (OSS-15).

/**
 * Timeout policy placeholder. OSS-18 adds the availability / time-to-first-token /
 * idle / validation layers and budget accounting per `timeout-retry-policy.md`.
 */
public data class TimeoutPolicy(
    public val requestTimeout: Duration? = null,
    public val attemptTimeout: Duration? = null,
) {
    init {
        require(requestTimeout == null || requestTimeout > Duration.ZERO) {
            "requestTimeout must be positive, was $requestTimeout"
        }
        require(attemptTimeout == null || attemptTimeout > Duration.ZERO) {
            "attemptTimeout must be positive, was $attemptTimeout"
        }
    }

    public companion object {
        public val Default: TimeoutPolicy = TimeoutPolicy()
    }
}

/**
 * Retry policy placeholder. Same-provider retries are disabled by default;
 * OSS-18 adds backoff and retryable-category configuration.
 */
public data class RetryPolicy(
    public val maxRetriesPerAttempt: Int = 0,
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
