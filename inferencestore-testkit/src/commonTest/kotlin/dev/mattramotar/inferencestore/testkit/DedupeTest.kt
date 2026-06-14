package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceExecutionConfig
import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.CachePolicy
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Request deduplication fan-out (OSS-14, `threading-dispatchers.md`). */
@OptIn(ExperimentalCoroutinesApi::class)
class DedupeTest {

    private val key = InferenceKey("notes.summary", "n1")
    private fun dedupeRequest() = InferenceRequest.text(key, "x").copy(cache = CachePolicy(allowDedupe = true))

    private fun store(provider: FakeInferenceProvider, dispatcher: kotlin.coroutines.CoroutineContext) =
        InferenceStore.build {
            provider(provider)
            policy = Policies.preferLocalThenCloud()
            executionConfig = InferenceExecutionConfig(providerContext = dispatcher)
        }

    @Test
    fun concurrentStream_joinBeforeContent_sharesOneInvocation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = fakeProvider("p", ProviderKind.Local) {
            delay(1.seconds)
            tokens("hi")
            complete("hi")
        }
        val store = store(provider, dispatcher)

        val a = async { store.stream(dedupeRequest()).toList() }
        runCurrent() // a creates the group; upstream parks at the delay (no content yet)
        val b = async { store.stream(dedupeRequest()).toList() }
        runCurrent() // b joins before first content
        advanceUntilIdle()

        assertEquals("hi", (a.await().last() as InferenceEvent.Done<*>).result.output)
        assertEquals("hi", (b.await().last() as InferenceEvent.Done<*>).result.output)
        provider.assertInvocations(1) // shared by both collectors
    }

    @Test
    fun lateCollector_afterContent_startsOwnCall() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = fakeProvider("p", ProviderKind.Local) {
            tokens("hi") // first content closes the join window immediately
            delay(1.seconds)
            complete("hi")
        }
        val store = store(provider, dispatcher)

        val a = async { store.stream(dedupeRequest()).toList() }
        runCurrent() // a emits first content -> join window closed, group released
        val b = async { store.stream(dedupeRequest()).toList() }
        runCurrent()
        advanceUntilIdle()

        a.await(); b.await()
        provider.assertInvocations(2) // b started its own call
    }

    @Test
    fun concurrentGenerate_sharesOneInvocation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = fakeProvider("p", ProviderKind.Local) {
            delay(1.seconds)
            complete("ok")
        }
        val store = store(provider, dispatcher)

        val a = async { store.generate(dedupeRequest()) }
        runCurrent()
        val b = async { store.generate(dedupeRequest()) }
        runCurrent()
        advanceUntilIdle()

        assertEquals("ok", a.await().output)
        assertEquals("ok", b.await().output)
        provider.assertInvocations(1)
    }

    @Test
    fun cancellingOneJoinedCollector_keepsUpstreamForTheOther() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = fakeProvider("p", ProviderKind.Local) {
            delay(1.seconds)
            complete("ok")
        }
        val store = store(provider, dispatcher)

        val a = launch { store.stream(dedupeRequest()).toList() }
        runCurrent()
        val b = async { store.stream(dedupeRequest()).toList() }
        runCurrent()
        a.cancel() // one joined collector leaves
        advanceUntilIdle()

        assertEquals("ok", (b.await().last() as InferenceEvent.Done<*>).result.output)
        provider.assertInvocations(1)
        assertFalse(provider.wasCancelled) // the remaining collector kept upstream alive
    }

    @Test
    fun cancellingAllJoinedCollectors_cancelsUpstreamOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = fakeProvider("p", ProviderKind.Local) { blockUntilCancelled() }
        val store = store(provider, dispatcher)

        val a = launch { store.stream(dedupeRequest()).toList() }
        val b = launch { store.stream(dedupeRequest()).toList() }
        runCurrent()
        a.cancel()
        b.cancel()
        advanceUntilIdle()

        provider.assertInvocations(1)
        provider.assertCancelled()
    }

    @Test
    fun dedupeDisabled_eachCollectorCallsProvider() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = fakeProvider("p", ProviderKind.Local) {
            delay(1.seconds)
            complete("ok")
        }
        val store = store(provider, dispatcher)

        val plain = InferenceRequest.text(key, "x") // allowDedupe defaults false
        val a = async { store.stream(plain).toList() }
        runCurrent()
        val b = async { store.stream(plain).toList() }
        runCurrent()
        advanceUntilIdle()

        a.await(); b.await()
        provider.assertInvocations(2)
    }
}
