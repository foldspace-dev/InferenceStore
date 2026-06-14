package dev.mattramotar.inferencestore.provider.litertlm

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.provider.ProviderExecutionBoundary
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import dev.mattramotar.inferencestore.core.provider.toProviderRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtLmProviderTest {

    private val key = InferenceKey("notes.summary", "n1")
    private val config = LiteRtLmProviderConfig(modelPath = "/models/gemma.litertlm", modelId = "gemma-2b")
    private fun textRequest() = InferenceRequest.text(key, "hi").toProviderRequest()

    private class FakeRuntime(
        val status: LiteRtLmStatus = LiteRtLmStatus.Ready,
        val tokens: List<String> = listOf("hi"),
        val error: LiteRtLmException? = null,
        val probeDelay: Duration = Duration.ZERO,
        val block: Boolean = false,
    ) : LiteRtLmRuntime {
        var cancelled: Boolean = false
            private set

        override suspend fun probe(modelPath: String, backend: LiteRtLmBackend): LiteRtLmStatus {
            if (probeDelay > Duration.ZERO) kotlinx.coroutines.delay(probeDelay)
            return status
        }

        override fun generate(modelPath: String, backend: LiteRtLmBackend, prompt: String): Flow<String> = flow {
            try {
                if (block) awaitCancellation()
                error?.let { throw it }
                tokens.forEach { emit(it) }
            } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                cancelled = true
                throw cancellation
            }
        }
    }

    @Test
    fun localBoundary_isLocalProcess() {
        val provider = LiteRtLmProvider(config, FakeRuntime())
        assertEquals(ProviderExecutionBoundary.LocalProcess, provider.boundary.execution)
        assertTrue(!provider.boundary.isCloudLike)
    }

    @Test
    fun missingModel_isUnavailable() = runTest {
        val provider = LiteRtLmProvider(config, FakeRuntime(status = LiteRtLmStatus.Unavailable(LiteRtLmFailure.MissingModel)))
        val availability = provider.availability(InferenceContext())
        assertTrue(availability is ProviderAvailability.Unavailable)
        assertEquals(UnavailableReason.ModelMissing, availability.reason)
    }

    @Test
    fun missingModel_throughStore_isProviderUnavailable() = runTest {
        val provider = LiteRtLmProvider(config, FakeRuntime(status = LiteRtLmStatus.Unavailable(LiteRtLmFailure.MissingModel)))
        val last = InferenceStore.single(provider).stream(InferenceRequest.text(key, "hi")).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.ProviderUnavailable, last.error.category)
    }

    @Test
    fun readyModel_streamsTokensAndMetadata() = runTest {
        val provider = LiteRtLmProvider(config, FakeRuntime(tokens = listOf("Hel", "lo")))
        val events = provider.stream(textRequest(), InferenceContext()).toList()
        assertTrue(events.first() is ProviderEvent.Started)
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ProviderEvent.Token>().map { it.text })
        val completed = events.last() as ProviderEvent.Completed<*>
        assertEquals("Hello", completed.output)
        assertEquals("gemma-2b", completed.metadata.modelId)
        assertEquals("Auto", completed.metadata.extra["backend"])
    }

    @Test
    fun integration_throughStore_streamsAndTraces() = runTest {
        val provider = LiteRtLmProvider(config, FakeRuntime(tokens = listOf("ok")))
        val result = InferenceStore.single(provider).generate(InferenceRequest.text(key, "hi"))
        assertEquals("ok", result.output)
        assertEquals("litertlm-local", result.trace?.finalProvider)
        assertEquals("gemma-2b", result.trace?.attempts?.first()?.modelId)
    }

    @Test
    fun unsupportedCapability_reportsUnsupported() = runTest {
        val provider = LiteRtLmProvider(config, FakeRuntime())
        val structuredRequest = InferenceRequest(
            key = key,
            input = InferenceInput.Text("hi"),
            output = OutputSpec.Custom { it }, // requires StructuredOutput, not an MVP capability
        )
        val report = provider.capabilities(structuredRequest, InferenceContext())
        assertTrue(!report.supported)
    }

    @Test
    fun mappedGenerationError_keepsCategory() = runTest {
        val provider = LiteRtLmProvider(config, FakeRuntime(error = LiteRtLmException(ErrorCategory.TransientProviderError, "native blip")))
        val last = provider.stream(textRequest(), InferenceContext()).toList().last()
        assertTrue(last is ProviderEvent.Failed)
        assertEquals(ErrorCategory.TransientProviderError, last.error.category)
    }

    @Test
    fun unmappedError_isTerminalUnknown() = runTest {
        val runtime = object : LiteRtLmRuntime {
            override suspend fun probe(modelPath: String, backend: LiteRtLmBackend) = LiteRtLmStatus.Ready
            override fun generate(modelPath: String, backend: LiteRtLmBackend, prompt: String): Flow<String> =
                flow { throw IllegalStateException("boom") }
        }
        val last = LiteRtLmProvider(config, runtime).stream(textRequest(), InferenceContext()).toList().last()
        assertTrue(last is ProviderEvent.Failed)
        assertEquals(ErrorCategory.Unknown, last.error.category)
    }

    @Test
    fun cancellation_closesConversation() = runTest {
        val runtime = FakeRuntime(block = true)
        val provider = LiteRtLmProvider(config, runtime)
        val job = launch { provider.stream(textRequest(), InferenceContext()).toList() }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(runtime.cancelled)
    }

    @Test
    fun probeTimeout_isUnavailable() = runTest {
        // Default availabilityTimeout is 500ms; the probe takes 2s -> times out.
        val runtime = FakeRuntime(status = LiteRtLmStatus.Ready, probeDelay = 2.seconds)
        val availability = LiteRtLmProvider(config, runtime).availability(InferenceContext())
        assertTrue(availability is ProviderAvailability.Unavailable)
        assertEquals(UnavailableReason.Unknown, availability.reason) // InitializationTimeout -> Unknown
    }
}
