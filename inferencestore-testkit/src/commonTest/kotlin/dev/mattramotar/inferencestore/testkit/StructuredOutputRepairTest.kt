package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.FallbackPolicy
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.validation.OutputValidators
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Structured-output validation + repair fallback (OSS-23, RFC-0006). */
class StructuredOutputRepairTest {

    private val key = InferenceKey("extract.summary", "n1")
    private val validJsonText = """{"value":"ok"}"""

    @Test
    fun malformedLocalJson_repairsOnCloud() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { complete("{ not json") }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete(validJsonText) }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.validateLocalThenCloudRepair()
        }

        val result = store.generate(
            InferenceRequest.text(
                key,
                "extract",
                fallback = FallbackPolicy(repairEnabled = true),
                validator = OutputValidators.wellFormedJson(),
            ),
        )

        assertEquals(validJsonText, result.output)
        assertRoute(result.trace) {
            failed("local", ErrorCategory.ParsingFailed)
            fellBackTo("cloud", FallbackReason.OutputParserFailed)
            completedWith("cloud")
        }
    }

    @Test
    fun malformedJson_isTerminal_withoutRepair() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { complete("{ not json") }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete(validJsonText) }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
        }

        // Repair disabled by default -> ParsingFailed is terminal, no cloud repair.
        val last = store.stream(
            InferenceRequest.text(key, "extract", validator = OutputValidators.wellFormedJson()),
        ).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.ParsingFailed, last.error.category)
        cloud.assertInvocations(0)
    }

    @Test
    fun wellFormedLocalJson_completesWithoutRepair() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { complete(validJsonText) }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete(validJsonText) }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.validateLocalThenCloudRepair()
        }

        val result = store.generate(
            InferenceRequest.text(
                key,
                "extract",
                fallback = FallbackPolicy(repairEnabled = true),
                validator = OutputValidators.wellFormedJson(),
            ),
        )
        assertEquals(validJsonText, result.output)
        cloud.assertInvocations(0)
    }
}
