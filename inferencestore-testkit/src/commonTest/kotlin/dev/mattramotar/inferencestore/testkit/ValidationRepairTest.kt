package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceException
import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.FallbackPolicy
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.validation.OutputValidator
import dev.mattramotar.inferencestore.core.validation.OutputValidators
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Validation-triggered fallback/repair (OSS-17). */
class ValidationRepairTest {

    private val key = InferenceKey("notes.summary", "n1")
    private val onlyGood = OutputValidators.predicate<String>("must be good") { it == "good" }

    private fun completing(id: String, kind: ProviderKind, text: String) =
        fakeProvider(id, kind) { complete(text) }

    @Test
    fun localInvalid_repairsOnCloud_whenRepairEnabled() = runTest {
        val local = completing("local", ProviderKind.Local, "bad")
        val cloud = completing("cloud", ProviderKind.Cloud, "good")
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.validateLocalThenCloudRepair()
        }

        val result = store.generate(
            InferenceRequest.text(key, "hi", fallback = FallbackPolicy(repairEnabled = true), validator = onlyGood),
        )
        assertEquals("good", result.output)
        assertRoute(result.trace) {
            attempted("local")
            failed("local", ErrorCategory.ValidationFailed)
            fellBackTo("cloud", FallbackReason.ValidatorRejected)
            completedWith("cloud")
        }
    }

    @Test
    fun localInvalid_isTerminal_withoutRepair() = runTest {
        val local = completing("local", ProviderKind.Local, "bad")
        val cloud = completing("cloud", ProviderKind.Cloud, "good")
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }

        // Repair disabled by default -> validation failure is terminal, no cloud repair.
        val last = store.stream(InferenceRequest.text(key, "hi", validator = onlyGood)).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.ValidationFailed, last.error.category)
        cloud.assertInvocations(0)
    }

    @Test
    fun validOutput_completesNormally_andEmitsValidationCompleted() = runTest {
        val local = completing("local", ProviderKind.Local, "good")
        val events = InferenceStore.single(local)
            .stream(InferenceRequest.text(key, "hi", validator = onlyGood)).toList()
        assertEvents(events) {
            started()
            providerAttemptStarted("local")
            validationCompleted()
            providerAttemptCompleted()
            done()
        }
    }

    @Test
    fun throwingValidator_failsAttemptDefensively_doesNotCrash() = runTest {
        val local = completing("local", ProviderKind.Local, "x")
        val cloud = completing("cloud", ProviderKind.Cloud, "good")
        val boom = OutputValidator<String> { _, _ -> throw IllegalStateException("validator boom") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }
        val request = InferenceRequest.text(key, "hi", fallback = FallbackPolicy(repairEnabled = true), validator = boom)

        // The exception is mapped to Unknown (terminal), not propagated as a raw crash.
        val last = store.stream(request).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.Unknown, last.error.category)
        cloud.assertInvocations(0) // Unknown is terminal, no repair

        val ex = assertFailsWith<InferenceException> { store.generate(request) }
        assertEquals(ErrorCategory.Unknown, ex.error.category)
    }

    @Test
    fun parserCategory_mapsToParsingFailed() = runTest {
        val local = completing("local", ProviderKind.Local, "junk")
        val parser = OutputValidators.predicate<String>("parse error", ErrorCategory.ParsingFailed) { false }
        val last = InferenceStore.single(local)
            .stream(InferenceRequest.text(key, "hi", validator = parser)).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.ParsingFailed, last.error.category)
    }

    @Test
    fun repairProviderAlsoInvalid_isTerminal() = runTest {
        val local = completing("local", ProviderKind.Local, "bad")
        val cloud = completing("cloud", ProviderKind.Cloud, "alsobad")
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.validateLocalThenCloudRepair()
        }

        val last = store.stream(
            InferenceRequest.text(key, "hi", fallback = FallbackPolicy(repairEnabled = true), validator = onlyGood),
        ).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.ValidationFailed, last.error.category)
        // Both attempts ran and both failed validation.
        assertRoute(last.trace) {
            failed("local", ErrorCategory.ValidationFailed)
            failed("cloud", ErrorCategory.ValidationFailed)
        }
    }
}
