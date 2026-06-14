package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.monitor.MonitorEvent
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.validation.OutputValidators
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Monitor projection + redaction (OSS-19). */
class MonitorTest {

    private val key = InferenceKey("notes.summary", "n1")

    @Test
    fun monitor_receivesLifecycleInOrder() = runTest {
        val events = mutableListOf<MonitorEvent>()
        val local = fakeProvider("local", ProviderKind.Local) {
            tokens("Hel", "lo")
            complete("Hello")
        }
        val store = InferenceStore.build {
            provider(local)
            monitor { events += it }
        }

        store.generate(InferenceRequest.text(key, "secret-prompt"))
        assertEquals(
            listOf(
                "RequestStarted", "RouteSelected", "ProviderAttemptStarted",
                "TokenEmitted", "TokenEmitted", "ProviderAttemptCompleted", "RequestCompleted",
            ),
            events.map { it::class.simpleName },
        )
    }

    @Test
    fun monitor_isRedacted_noRawContent() = runTest {
        val events = mutableListOf<MonitorEvent>()
        val local = fakeProvider("local", ProviderKind.Local) {
            tokens("Hel", "lo")
            complete("Hello")
        }
        InferenceStore.build {
            provider(local)
            monitor { events += it }
        }.generate(InferenceRequest.text(key, "secret-prompt"))

        // Token events carry a cumulative count, never the text.
        assertEquals(listOf(1, 2), events.filterIsInstance<MonitorEvent.TokenEmitted>().map { it.count })
        // No raw prompt/output anywhere in the monitor stream.
        assertTrue(events.none { it.toString().contains("Hello") || it.toString().contains("secret-prompt") })
    }

    @Test
    fun monitor_requestFailed_carriesCategoryOnly() = runTest {
        val events = mutableListOf<MonitorEvent>()
        val provider = fakeProvider("p", ProviderKind.Local) { fail(ErrorCategory.RateLimited, "secret-error-body") }
        val last = InferenceStore.build {
            provider(provider)
            monitor { events += it }
        }.stream(InferenceRequest.text(key, "hi")).toList().last()

        assertTrue(last is InferenceEvent.Failed)
        val failed = events.filterIsInstance<MonitorEvent.RequestFailed>().single()
        assertEquals(ErrorCategory.RateLimited, failed.error)
        assertTrue(events.none { it.toString().contains("secret-error-body") })
    }

    @Test
    fun monitor_observesFallbackAndValidation() = runTest {
        val events = mutableListOf<MonitorEvent>()
        val a = fakeProvider("a", ProviderKind.Local) { fail(ErrorCategory.TransientProviderError) }
        val b = fakeProvider("b", ProviderKind.Local) { complete("ok") }
        InferenceStore.build {
            provider(a)
            provider(b)
            policy = Policies.preferLocalThenCloud()
            monitor { events += it }
        }.generate(InferenceRequest.text(key, "hi", validator = OutputValidators.nonBlankText))

        assertTrue(events.any { it is MonitorEvent.FallbackStarted })
        assertTrue(events.any { it is MonitorEvent.ValidationCompleted })
    }

    @Test
    fun throwingMonitor_doesNotBreakTheRequest() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) { complete("ok") }
        val result = InferenceStore.build {
            provider(local)
            monitor { throw IllegalStateException("monitor boom") }
        }.generate(InferenceRequest.text(key, "hi"))
        assertEquals("ok", result.output)
    }
}
