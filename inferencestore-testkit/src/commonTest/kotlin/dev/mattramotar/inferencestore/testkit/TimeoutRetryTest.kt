package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.InferenceException
import dev.mattramotar.inferencestore.core.InferenceExecutionConfig
import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.FallbackReason
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.BackoffPolicy
import dev.mattramotar.inferencestore.core.policy.Policies
import dev.mattramotar.inferencestore.core.policy.RetryPolicy
import dev.mattramotar.inferencestore.core.policy.TimeoutPolicy
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Timeout, retry, and budget semantics (OSS-18). */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeoutRetryTest {

    private val key = InferenceKey("notes.summary", "n1")

    @Test
    fun attemptTimeout_fallsBackToNext() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) {
            delay(10.seconds)
            complete("local")
        }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("cloud") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
            executionConfig = InferenceExecutionConfig(timeSource = testTimeSource)
        }

        val result = store.generate(
            InferenceRequest.text(key, "hi").copy(timeout = TimeoutPolicy(attemptTimeout = 1.seconds)),
        )
        assertEquals("cloud", result.output)
        assertRoute(result.trace) {
            attempted("local")
            fellBackTo("cloud", FallbackReason.Timeout)
            completedWith("cloud")
        }
    }

    @Test
    fun requestTimeout_isTerminal_andSkipsFallback() = runTest {
        val local = fakeProvider("local", ProviderKind.Local) {
            delay(10.seconds)
            complete("local")
        }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("cloud") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
            executionConfig = InferenceExecutionConfig(timeSource = testTimeSource)
        }

        val last = store.stream(
            InferenceRequest.text(key, "hi").copy(timeout = TimeoutPolicy(requestTimeout = 1.seconds)),
        ).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.Timeout, last.error.category)
        assertEquals(ErrorSource.RequestDeadlineExceeded, last.error.source)
        cloud.assertInvocations(0) // deadline exhausted -> no fallback
    }

    @Test
    fun rateLimitRetry_isObservable_andUsesVirtualClock() = runTest {
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { fail(ErrorCategory.RateLimited) }
        val store = InferenceStore.build {
            provider(cloud)
            policy = Policies.preferCloudThenLocal()
            executionConfig = InferenceExecutionConfig(timeSource = testTimeSource)
        }
        val retry = RetryPolicy(
            maxRetriesPerAttempt = 1,
            retryableCategories = setOf(ErrorCategory.RateLimited),
            backoff = BackoffPolicy.Fixed(2.seconds),
        )

        val events = store.stream(InferenceRequest.text(key, "hi").copy(retry = retry)).toList()
        val retries = events.filterIsInstance<InferenceEvent.RetryScheduled>()
        assertEquals(1, retries.size)
        assertEquals(2.seconds, retries[0].delay)
        cloud.assertInvocations(2) // original attempt + one retry
        assertTrue(events.last() is InferenceEvent.Failed)
    }

    @Test
    fun retry_skippedWhenDelayExceedsRequestBudget() = runTest {
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { fail(ErrorCategory.RateLimited) }
        val store = InferenceStore.build {
            provider(cloud)
            policy = Policies.preferCloudThenLocal()
            executionConfig = InferenceExecutionConfig(timeSource = testTimeSource)
        }
        val retry = RetryPolicy(
            maxRetriesPerAttempt = 1,
            retryableCategories = setOf(ErrorCategory.RateLimited),
            backoff = BackoffPolicy.Fixed(5.seconds),
        )

        val events = store.stream(
            InferenceRequest.text(key, "hi").copy(retry = retry, timeout = TimeoutPolicy(requestTimeout = 1.seconds)),
        ).toList()
        assertTrue(events.none { it is InferenceEvent.RetryScheduled }) // 5s delay does not fit 1s budget
        cloud.assertInvocations(1)
        assertTrue(events.last() is InferenceEvent.Failed)
    }

    @Test
    fun callerCancellation_isNotMappedToTimeout() = runTest {
        val slow = fakeProvider("slow", ProviderKind.Local) { blockUntilCancelled() }
        val store = InferenceStore.build {
            provider(slow)
            policy = Policies.preferLocalThenCloud()
            executionConfig = InferenceExecutionConfig(timeSource = testTimeSource)
        }

        val job = launch {
            store.generate(InferenceRequest.text(key, "hi").copy(timeout = TimeoutPolicy(attemptTimeout = 30.seconds)))
        }
        runCurrent() // start collection; provider blocks at awaitCancellation
        job.cancel()
        job.join()

        assertTrue(job.isCancelled) // cancellation honored, not a timeout result
        slow.assertCancelled()
    }

    @Test
    fun timeoutAfterTokens_failsAttempt() = runTest {
        // Tokens stream live, then the attempt stalls and times out.
        val local = fakeProvider("local", ProviderKind.Local) {
            tokens("par", "tial")
            delay(10.seconds)
            complete("local")
        }
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("cloud") }
        val store = InferenceStore.build {
            provider(local)
            provider(cloud)
            policy = Policies.preferLocalThenCloud()
            executionConfig = InferenceExecutionConfig(timeSource = testTimeSource)
        }

        val events = store.stream(
            InferenceRequest.text(key, "hi").copy(timeout = TimeoutPolicy(attemptTimeout = 1.seconds)),
        ).toList()
        // Live tokens were emitted before the timeout, then it fell back and completed on cloud.
        assertTrue(events.any { it is InferenceEvent.Token && it.text == "par" })
        assertTrue(events.last() is InferenceEvent.Done<*>)
        assertRoute((events.last() as InferenceEvent.Done<*>).result.trace) {
            fellBackTo("cloud", FallbackReason.Timeout)
        }
    }
}
