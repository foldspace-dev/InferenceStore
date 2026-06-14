package dev.mattramotar.inferencestore.core.policy

import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource

/**
 * The default disposition for an [ErrorCategory] (`error-fallback-mapping.md`).
 *
 * Same-provider retry is disabled by default in MVP ("prefer fallback over hidden
 * retries"), so the disposition models fallback vs terminal; per-category retry
 * arrives with OSS-18.
 */
public data class FallbackDisposition(
    public val category: ErrorCategory,
    public val fallbackAllowed: Boolean,
    public val reason: FallbackReason,
)

/**
 * Per-request fallback configuration. A policy may **restrict** fallback (disable
 * categories) or opt into validation/parsing **repair**, but it cannot make a
 * terminal category fall back for any other reason or change a category's fallback
 * reason — the category semantics in [FallbackMapping] are canonical.
 */
public data class FallbackPolicy(
    public val disabledCategories: Set<ErrorCategory> = emptySet(),
    public val repairEnabled: Boolean = false,
) {
    public companion object {
        public val Default: FallbackPolicy = FallbackPolicy()
    }
}

/**
 * Canonical `ErrorCategory` → fallback disposition (`error-fallback-mapping.md`).
 *
 * This is the single source of truth the engine consults; routing/fallback policy
 * may restrict it (see [FallbackPolicy]) but must not redefine it.
 */
public object FallbackMapping {

    /** The canonical [FallbackReason] for [category] (used when fallback is taken). */
    public fun reasonFor(category: ErrorCategory): FallbackReason = when (category) {
        ErrorCategory.ProviderUnavailable -> FallbackReason.ProviderUnavailable
        ErrorCategory.CapabilityUnsupported -> FallbackReason.CapabilityUnsupported
        ErrorCategory.Timeout -> FallbackReason.Timeout
        ErrorCategory.RateLimited -> FallbackReason.RateLimited
        ErrorCategory.TransientProviderError -> FallbackReason.TransientError
        ErrorCategory.PermanentProviderError -> FallbackReason.PermanentError
        ErrorCategory.ValidationFailed -> FallbackReason.ValidatorRejected
        ErrorCategory.ParsingFailed -> FallbackReason.OutputParserFailed
        ErrorCategory.PolicyViolation -> FallbackReason.PolicyViolation
        // Cancelled never falls back; the reason is a placeholder that is not surfaced.
        ErrorCategory.Cancelled -> FallbackReason.Unknown
        ErrorCategory.Unknown -> FallbackReason.Unknown
    }

    /**
     * The canonical default disposition for [category], refined by [source] where
     * the table distinguishes sources, and by whether the policy enabled
     * validation/parsing [repair]. This is the *default* — call [isFallbackAllowed]
     * to also apply a [FallbackPolicy]'s restrictions.
     */
    public fun dispositionFor(
        category: ErrorCategory,
        source: ErrorSource? = null,
        repair: Boolean = false,
    ): FallbackDisposition {
        val fallbackAllowed = when (category) {
            ErrorCategory.ProviderUnavailable,
            ErrorCategory.CapabilityUnsupported,
            ErrorCategory.RateLimited,
            ErrorCategory.TransientProviderError,
            -> true
            // Privacy is enforced before invocation; a runtime PolicyViolation does
            // not retry the denied provider, but the route may try another candidate.
            ErrorCategory.PolicyViolation -> true
            // Attempt timeout may fall back; request-deadline exhaustion is terminal.
            ErrorCategory.Timeout -> source != ErrorSource.RequestDeadlineExceeded
            // Permanent errors fall back only when the adapter says provider-specific.
            ErrorCategory.PermanentProviderError -> source == ErrorSource.ProviderSpecific
            // Validation/parsing fall back only when the policy enables repair (OSS-17).
            ErrorCategory.ValidationFailed,
            ErrorCategory.ParsingFailed,
            -> repair
            ErrorCategory.Cancelled,
            ErrorCategory.Unknown,
            -> false
        }
        return FallbackDisposition(category, fallbackAllowed, reasonFor(category))
    }

    /**
     * Whether fallback is allowed for [category]/[source] under [policy]. A policy
     * can only narrow the canonical default (disable a category), never widen it
     * beyond the [FallbackPolicy.repairEnabled] opt-in the table itself permits.
     */
    public fun isFallbackAllowed(
        category: ErrorCategory,
        source: ErrorSource? = null,
        policy: FallbackPolicy = FallbackPolicy.Default,
    ): Boolean = category !in policy.disabledCategories &&
        dispositionFor(category, source, policy.repairEnabled).fallbackAllowed
}
