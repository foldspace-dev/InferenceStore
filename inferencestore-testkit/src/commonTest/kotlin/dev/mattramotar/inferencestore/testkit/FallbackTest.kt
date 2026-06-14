package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.FallbackPolicy
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Engine behavior for the error->fallback mapping (OSS-16). */
class FallbackTest {

    private val key = InferenceKey("notes.summary", "n1")
    private fun req(fallback: FallbackPolicy = FallbackPolicy.Default) =
        InferenceRequest.text(key, "hi", fallback = fallback)

    // Both providers are Local so preferLocalThenCloud keeps them in registration order.
    private fun good(id: String) = fakeProvider(id, ProviderKind.Local) { complete("from-$id") }
    private fun failing(id: String, category: ErrorCategory, source: ErrorSource? = null) =
        fakeProvider(id, ProviderKind.Local) { fail(category, source = source) }

    private fun store(vararg providers: FakeInferenceProvider) = InferenceStore.build {
        providers.forEach { provider(it) }
        policy = Policies.preferLocalThenCloud()
    }

    @Test
    fun transientError_fallsBackToNext() = runTest {
        val a = failing("a", ErrorCategory.TransientProviderError)
        val b = good("b")
        val result = store(a, b).generate(req())
        assertEquals("from-b", result.output)
        assertRoute(result.trace) {
            attempted("a")
            fellBackTo("b", FallbackReason.TransientError)
            completedWith("b")
        }
    }

    @Test
    fun permanentError_isTerminalByDefault() = runTest {
        val a = failing("a", ErrorCategory.PermanentProviderError)
        val b = good("b")
        val last = store(a, b).stream(req()).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.PermanentProviderError, last.error.category)
        assertRoute(last.trace) { didNotAttempt("b") }
        b.assertInvocations(0)
    }

    @Test
    fun permanentProviderSpecific_fallsBack() = runTest {
        val a = failing("a", ErrorCategory.PermanentProviderError, ErrorSource.ProviderSpecific)
        val b = good("b")
        val result = store(a, b).generate(req())
        assertEquals("from-b", result.output)
        assertRoute(result.trace) { fellBackTo("b", FallbackReason.PermanentError) }
    }

    @Test
    fun timeoutDeadlineExceeded_isTerminal() = runTest {
        val a = failing("a", ErrorCategory.Timeout, ErrorSource.RequestDeadlineExceeded)
        val b = good("b")
        val last = store(a, b).stream(req()).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        b.assertInvocations(0)
    }

    @Test
    fun validationFailed_terminalByDefault_butFallsBackWithRepair() = runTest {
        val a = failing("a", ErrorCategory.ValidationFailed)
        val b = good("b")
        val last = store(a, b).stream(req()).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        b.assertInvocations(0)

        // Same scenario with repair enabled -> falls back to a repair provider.
        val a2 = failing("a", ErrorCategory.ValidationFailed)
        val b2 = good("b")
        val result = store(a2, b2).generate(req(FallbackPolicy(repairEnabled = true)))
        assertEquals("from-b", result.output)
        assertRoute(result.trace) { fellBackTo("b", FallbackReason.ValidatorRejected) }
    }

    @Test
    fun policy_canDisableFallbackForACategory() = runTest {
        val a = failing("a", ErrorCategory.RateLimited)
        val b = good("b")
        // RateLimited normally falls back; the request disables it.
        val last = store(a, b).stream(req(FallbackPolicy(disabledCategories = setOf(ErrorCategory.RateLimited))))
            .toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.RateLimited, last.error.category)
        b.assertInvocations(0)
    }

    @Test
    fun cancellationCategory_fromProvider_isTerminal() = runTest {
        // A provider that reports the Cancelled category (not caller cancellation) is terminal.
        val a = failing("a", ErrorCategory.Cancelled)
        val b = good("b")
        val last = store(a, b).stream(req()).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        b.assertInvocations(0)
    }
}
