package dev.mattramotar.inferencestore.core.event

import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import dev.mattramotar.inferencestore.core.provider.ProviderError
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Errors may carry raw causes/messages for debug hooks but must not leak them via
 * `toString()` (`error-fallback-mapping.md` / `security-privacy.md`).
 */
class ErrorRedactionTest {

    @Test
    fun inferenceError_toString_omitsMessageAndCause() {
        val error = InferenceError(
            category = ErrorCategory.PermanentProviderError,
            message = "SECRET-API-KEY-LEAK",
            cause = IllegalStateException("RAW-PROVIDER-BODY"),
            source = ErrorSource.RequestInvalid,
        )
        val text = error.toString()
        assertFalse(text.contains("SECRET-API-KEY-LEAK"), "message leaked: $text")
        assertFalse(text.contains("RAW-PROVIDER-BODY"), "cause leaked: $text")
        // Stable taxonomy is still surfaced.
        assertTrue(text.contains("PermanentProviderError"))
        assertTrue(text.contains("RequestInvalid"))
    }

    @Test
    fun providerError_toString_omitsMessageAndCause() {
        val error = ProviderError(
            category = ErrorCategory.RateLimited,
            message = "SECRET-API-KEY-LEAK",
            cause = IllegalStateException("RAW-PROVIDER-BODY"),
            source = ErrorSource.ProviderSpecific,
        )
        val text = error.toString()
        assertFalse(text.contains("SECRET-API-KEY-LEAK"), "message leaked: $text")
        assertFalse(text.contains("RAW-PROVIDER-BODY"), "cause leaked: $text")
        assertTrue(text.contains("RateLimited"))
        // The raw values remain available to debug hooks.
        assertTrue(error.message == "SECRET-API-KEY-LEAK")
    }
}
