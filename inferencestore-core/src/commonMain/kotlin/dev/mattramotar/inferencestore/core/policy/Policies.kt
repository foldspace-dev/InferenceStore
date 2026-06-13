package dev.mattramotar.inferencestore.core.policy

import kotlin.time.Duration

/**
 * Privacy policy placeholder.
 *
 * OSS-15 implements the canonical model from `privacy-model.md` —
 * `CloudPermission`, `PersistencePermission`, `TelemetryPermission`,
 * provider-boundary checks, and execution-controller enforcement. This slice
 * carries the [privacyClass] so the request model is type-complete.
 */
public data class PrivacyPolicy(
    public val privacyClass: PrivacyClass = PrivacyClass.Personal,
) {
    public companion object {
        /**
         * Strict default per `privacy-model.md`: `Personal` data, cloud denied,
         * no prompt/output persistence. Full enforcement lands in OSS-15.
         */
        public val Default: PrivacyPolicy = PrivacyPolicy(PrivacyClass.Personal)

        /** Convenience for harmless public/demo content; production should be explicit. */
        public fun publicData(): PrivacyPolicy = PrivacyPolicy(PrivacyClass.Public)
    }
}

/** Canonical privacy classes (`privacy-model.md`). `Custom(value)` arrives with OSS-15. */
public enum class PrivacyClass { Public, Internal, Personal, Sensitive, LocalOnly }

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

/**
 * Routing policy marker. OSS-13 implements the five built-in presets and the
 * suspend policy contract from RFC-0003.
 */
public interface InferencePolicy
