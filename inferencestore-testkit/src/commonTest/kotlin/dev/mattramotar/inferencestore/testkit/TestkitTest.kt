package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestkitTest {

    private val key = InferenceKey("notes.summary", "n1")

    @Test
    fun success_assertsRouteAndCanonicalEvents() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) {
            modelId = "gemma-2b"
            tokens("hel", "lo")
            complete("hello")
        }
        val store = InferenceStore.single(local)

        val events = store.stream(InferenceRequest.text(key, "hi")).toList()
        assertEvents(events) {
            started()
            providerAttemptStarted("local")
            token("hel")
            token("lo")
            providerAttemptCompleted()
            done()
        }

        val result = store.generate(InferenceRequest.text(key, "hi"))
        assertEquals("hello", result.output)
        assertRoute(result.trace) {
            attempted("local")
            completedWith("local")
            didNotAttempt("cloud")
        }
        local.assertInvocations(2) // one stream() + one generate()
    }

    @Test
    fun failure_assertsRoute() = runTest {
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) {
            fail(ErrorCategory.RateLimited, "429")
        }
        val events = InferenceStore.single(cloud).stream(InferenceRequest.text(key, "hi")).toList()

        val last = events.last()
        assertTrue(last is InferenceEvent.Failed)
        assertRoute(last.trace) {
            failed("cloud", ErrorCategory.RateLimited)
            didNotAttempt("local")
        }
    }

    @Test
    fun blockUntilCancelled_marksProviderCancelled_andDoesNotComplete() = runTest {
        val slow = fakeProvider("slow") { blockUntilCancelled() }
        val store = InferenceStore.single(slow)

        val job = launch { store.generate(InferenceRequest.text(key, "hi")) }
        runCurrent() // start collection; provider suspends at awaitCancellation
        job.cancel()
        job.join()

        slow.assertCancelled()
        slow.assertInvocations(1)
    }
}
