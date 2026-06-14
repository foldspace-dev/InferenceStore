package dev.mattramotar.inferencestore.core.policy

import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The canonical error->fallback mapping (OSS-16, `error-fallback-mapping.md`). */
class FallbackMappingTest {

    private fun fallback(category: ErrorCategory, source: ErrorSource? = null, repair: Boolean = false) =
        FallbackMapping.dispositionFor(category, source, repair).fallbackAllowed

    @Test
    fun defaultDispositions_matchTheTable() {
        // Fallback by default.
        assertTrue(fallback(ErrorCategory.ProviderUnavailable))
        assertTrue(fallback(ErrorCategory.CapabilityUnsupported))
        assertTrue(fallback(ErrorCategory.RateLimited))
        assertTrue(fallback(ErrorCategory.TransientProviderError))
        assertTrue(fallback(ErrorCategory.PolicyViolation)) // route may try another candidate
        assertTrue(fallback(ErrorCategory.Timeout)) // attempt-timeout assumption with no source

        // Terminal by default.
        assertFalse(fallback(ErrorCategory.PermanentProviderError)) // unless provider-specific
        assertFalse(fallback(ErrorCategory.ValidationFailed)) // unless repair enabled
        assertFalse(fallback(ErrorCategory.ParsingFailed)) // unless repair enabled
        assertFalse(fallback(ErrorCategory.Cancelled))
        assertFalse(fallback(ErrorCategory.Unknown))
    }

    @Test
    fun errorSource_refinesTimeoutAndPermanent() {
        assertTrue(fallback(ErrorCategory.Timeout, ErrorSource.AttemptTimeout))
        assertFalse(fallback(ErrorCategory.Timeout, ErrorSource.RequestDeadlineExceeded))
        assertTrue(fallback(ErrorCategory.PermanentProviderError, ErrorSource.ProviderSpecific))
        assertFalse(fallback(ErrorCategory.PermanentProviderError, ErrorSource.RequestInvalid))
    }

    @Test
    fun repair_enablesValidationAndParsingFallback() {
        assertFalse(fallback(ErrorCategory.ValidationFailed, repair = false))
        assertTrue(fallback(ErrorCategory.ValidationFailed, repair = true))
        assertFalse(fallback(ErrorCategory.ParsingFailed, repair = false))
        assertTrue(fallback(ErrorCategory.ParsingFailed, repair = true))
    }

    @Test
    fun reasonFor_mapsEveryCategoryToTheCanonicalReason() {
        assertEquals(FallbackReason.ProviderUnavailable, FallbackMapping.reasonFor(ErrorCategory.ProviderUnavailable))
        assertEquals(FallbackReason.CapabilityUnsupported, FallbackMapping.reasonFor(ErrorCategory.CapabilityUnsupported))
        assertEquals(FallbackReason.Timeout, FallbackMapping.reasonFor(ErrorCategory.Timeout))
        assertEquals(FallbackReason.RateLimited, FallbackMapping.reasonFor(ErrorCategory.RateLimited))
        assertEquals(FallbackReason.TransientError, FallbackMapping.reasonFor(ErrorCategory.TransientProviderError))
        assertEquals(FallbackReason.PermanentError, FallbackMapping.reasonFor(ErrorCategory.PermanentProviderError))
        assertEquals(FallbackReason.ValidatorRejected, FallbackMapping.reasonFor(ErrorCategory.ValidationFailed))
        assertEquals(FallbackReason.OutputParserFailed, FallbackMapping.reasonFor(ErrorCategory.ParsingFailed))
        assertEquals(FallbackReason.PolicyViolation, FallbackMapping.reasonFor(ErrorCategory.PolicyViolation))
        assertEquals(FallbackReason.Unknown, FallbackMapping.reasonFor(ErrorCategory.Cancelled))
        assertEquals(FallbackReason.Unknown, FallbackMapping.reasonFor(ErrorCategory.Unknown))
    }

    @Test
    fun policy_canRestrictButNotRedefine() {
        // A policy can disable a normally-fallback category.
        assertTrue(FallbackMapping.isFallbackAllowed(ErrorCategory.RateLimited))
        assertFalse(
            FallbackMapping.isFallbackAllowed(
                ErrorCategory.RateLimited,
                policy = FallbackPolicy(disabledCategories = setOf(ErrorCategory.RateLimited)),
            ),
        )
        // A policy cannot make a terminal category fall back (only the documented repair opt-in can).
        assertFalse(FallbackMapping.isFallbackAllowed(ErrorCategory.Unknown))
        assertFalse(
            FallbackMapping.isFallbackAllowed(
                ErrorCategory.Unknown,
                policy = FallbackPolicy(repairEnabled = true),
            ),
        )
        // The repair opt-in enables exactly validation/parsing.
        assertFalse(FallbackMapping.isFallbackAllowed(ErrorCategory.ValidationFailed))
        assertTrue(
            FallbackMapping.isFallbackAllowed(
                ErrorCategory.ValidationFailed,
                policy = FallbackPolicy(repairEnabled = true),
            ),
        )
    }

    @Test
    fun everyCategoryAndSourceCombinationHasADisposition() {
        for (category in ErrorCategory.entries) {
            FallbackMapping.reasonFor(category)
            FallbackMapping.dispositionFor(category)
            for (source in ErrorSource.entries) {
                FallbackMapping.dispositionFor(category, source)
            }
        }
    }
}
