package dev.mattramotar.inferencestore.core

import dev.mattramotar.inferencestore.core.event.AttemptOutcome
import dev.mattramotar.inferencestore.core.event.FinalStatus
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.provider.Capability
import dev.mattramotar.inferencestore.core.provider.CapabilityReport
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderError
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderMetadata
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamingEngineTest {

    private val key = InferenceKey("notes.summary", "n1")

    private fun echoProvider(onStream: () -> Unit = {}) = object : InferenceProvider {
        override val id = ProviderId("echo")
        override val kind = ProviderKind.Test
        override val boundary = ProviderPrivacyBoundary.localDevice()
        override suspend fun availability(context: InferenceContext) = ProviderAvailability.Available
        override suspend fun capabilities(request: InferenceRequest<*>, context: InferenceContext) =
            CapabilityReport(supported = true, capabilities = setOf(Capability.TextGeneration, Capability.Streaming))

        override fun <Output : Any> stream(
            request: ProviderRequest<Output>,
            context: InferenceContext,
        ): Flow<ProviderEvent<Output>> = flow {
            onStream()
            val meta = ProviderMetadata(id, kind, boundary, modelId = "echo-1")
            emit(ProviderEvent.Started(meta))
            val text = (request.input as InferenceInput.Text).value
            emit(ProviderEvent.Token(text))
            @Suppress("UNCHECKED_CAST")
            emit(ProviderEvent.Completed(text as Output, rawText = text, metadata = meta))
        }
    }

    // Local boundary so these failure-handling tests are privacy-neutral; the
    // privacy gate's interaction with cloud providers is covered in PrivacyTest.
    private val failing = object : InferenceProvider {
        override val id = ProviderId("boom")
        override val kind = ProviderKind.Test
        override val boundary = ProviderPrivacyBoundary.localDevice()
        override suspend fun availability(context: InferenceContext) = ProviderAvailability.Available
        override suspend fun capabilities(request: InferenceRequest<*>, context: InferenceContext) =
            CapabilityReport(supported = true, capabilities = setOf(Capability.TextGeneration))

        override fun <Output : Any> stream(
            request: ProviderRequest<Output>,
            context: InferenceContext,
        ): Flow<ProviderEvent<Output>> = flow {
            emit(ProviderEvent.Failed(ProviderError(ErrorCategory.RateLimited, "429")))
        }
    }

    private val throwing = object : InferenceProvider {
        override val id = ProviderId("throws")
        override val kind = ProviderKind.Test
        override val boundary = ProviderPrivacyBoundary.localDevice()
        override suspend fun availability(context: InferenceContext) = ProviderAvailability.Available
        override suspend fun capabilities(request: InferenceRequest<*>, context: InferenceContext) =
            CapabilityReport(supported = true, capabilities = setOf(Capability.TextGeneration))

        override fun <Output : Any> stream(
            request: ProviderRequest<Output>,
            context: InferenceContext,
        ): Flow<ProviderEvent<Output>> = flow { throw IllegalStateException("boom") }
    }

    @Test
    fun providerThatThrows_isMappedToFailed_andGenerateThrows() = runTest {
        val store = InferenceStore.single(throwing)
        val events = store.stream(InferenceRequest.text(key, "hi")).toList()

        val last = events.last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.Unknown, last.error.category)
        // The canonical terminal pair is still emitted even though the provider threw.
        assertTrue(events[events.size - 2] is InferenceEvent.ProviderAttemptCompleted)

        val ex = assertFailsWith<InferenceException> { store.generate(InferenceRequest.text(key, "hi")) }
        assertEquals(ErrorCategory.Unknown, ex.error.category)
    }

    @Test
    fun stream_emitsCanonicalOrder() = runTest {
        val store = InferenceStore.single(echoProvider())
        val events = store.stream(InferenceRequest.text(key, "hello")).toList()

        assertTrue(events[0] is InferenceEvent.Started)
        assertTrue(events[1] is InferenceEvent.ProviderAttemptStarted)
        assertTrue(events.any { it is InferenceEvent.Token })
        assertTrue(events[events.size - 2] is InferenceEvent.ProviderAttemptCompleted)

        val done = events.last()
        assertTrue(done is InferenceEvent.Done)
        assertEquals("hello", done.result.output)
    }

    @Test
    fun generate_returnsResult() = runTest {
        val store = InferenceStore.single(echoProvider())
        assertEquals("hi", store.generate(InferenceRequest.text(key, "hi")).output)
    }

    @Test
    fun failure_emitsCanonicalOrder_andGenerateThrows() = runTest {
        val store = InferenceStore.single(failing)
        val events = store.stream(InferenceRequest.text(key, "hi")).toList()

        // Started -> ProviderAttemptStarted -> ProviderAttemptCompleted(failed) -> Failed
        assertEquals(4, events.size)
        assertTrue(events[0] is InferenceEvent.Started)
        assertTrue(events[1] is InferenceEvent.ProviderAttemptStarted)
        val completed = events[2]
        assertTrue(completed is InferenceEvent.ProviderAttemptCompleted)
        assertEquals(AttemptOutcome.Failed, completed.attempt.outcome)
        assertEquals(ErrorCategory.RateLimited, completed.attempt.error)
        assertTrue(events[3] is InferenceEvent.Failed)

        val ex = assertFailsWith<InferenceException> { store.generate(InferenceRequest.text(key, "hi")) }
        assertEquals(ErrorCategory.RateLimited, ex.error.category)
    }

    @Test
    fun stream_isCold_noProviderWorkBeforeCollection() = runTest {
        var calls = 0
        val store = InferenceStore.single(echoProvider(onStream = { calls++ }))
        val flow = store.stream(InferenceRequest.text(key, "hi"))
        assertEquals(0, calls)
        flow.toList()
        assertEquals(1, calls)
    }

    @Test
    fun cancellation_isTerminal_takeFirstEventsOnly() = runTest {
        val store = InferenceStore.single(echoProvider())
        val firstTwo = store.stream(InferenceRequest.text(key, "hi")).take(2).toList()
        assertEquals(2, firstTwo.size)
        assertTrue(firstTwo[0] is InferenceEvent.Started)
    }

    @Test
    fun success_attachesRouteTrace() = runTest {
        val result = InferenceStore.single(echoProvider()).generate(InferenceRequest.text(key, "hi"))
        val trace = result.trace
        assertTrue(trace != null)
        assertEquals(FinalStatus.Succeeded, trace.finalStatus)
        assertEquals("echo", trace.finalProvider)
        assertEquals(1, trace.attempts.size)
        assertEquals(AttemptOutcome.Succeeded, trace.attempts[0].outcome)
        assertEquals("echo-1", trace.attempts[0].modelId)
    }

    @Test
    fun failure_attachesRouteTraceWithErrorCategory() = runTest {
        val last = InferenceStore.single(failing).stream(InferenceRequest.text(key, "hi")).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        val trace = last.trace
        assertTrue(trace != null)
        assertEquals(FinalStatus.Failed, trace.finalStatus)
        assertEquals(ErrorCategory.RateLimited, trace.attempts[0].errorCategory)
    }
}
