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
    public val privacyClass: PrivacyClass = PrivacyClass.Public,
) {
    public companion object {
        public val Default: PrivacyPolicy = PrivacyPolicy()
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
