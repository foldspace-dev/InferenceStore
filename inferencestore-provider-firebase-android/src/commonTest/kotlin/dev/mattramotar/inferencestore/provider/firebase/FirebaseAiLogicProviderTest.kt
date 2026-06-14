package dev.mattramotar.inferencestore.provider.firebase

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
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
class FirebaseAiLogicProviderTest {

    private val key = InferenceKey("notes.summary", "n1")
    private val config = FirebaseAiConfig(modelId = "gemini")
    private fun textRequest() = InferenceRequest.text(key, "hi").toProviderRequest()
    private fun chunks(source: FirebaseAiSource, vararg texts: String) = texts.map { FirebaseAiChunk(it, source) }

    private class FakeRuntime(
        val status: FirebaseAiStatus = FirebaseAiStatus.Ready,
        val chunks: List<FirebaseAiChunk> = listOf(FirebaseAiChunk("hi", FirebaseAiSource.OnDevice)),
        val error: FirebaseAiException? = null,
        val probeDelay: Duration = Duration.ZERO,
        val block: Boolean = false,
    ) : FirebaseAiRuntime {
        var cancelled: Boolean = false
            private set

        override suspend fun probe(config: FirebaseAiConfig): FirebaseAiStatus {
            if (probeDelay > Duration.ZERO) kotlinx.coroutines.delay(probeDelay)
            return status
        }

        override fun generate(config: FirebaseAiConfig, prompt: String): Flow<FirebaseAiChunk> = flow {
            try {
                if (block) awaitCancellation()
                error?.let { throw it }
                chunks.forEach { emit(it) }
            } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                cancelled = true
                throw cancellation
            }
        }
    }

    @Test
    fun hybridBoundary_isCloudLike() {
        // The hybrid may use cloud, so its boundary must be cloud-like for the gate.
        val provider = FirebaseAiLogicProvider(config, FakeRuntime())
        assertTrue(provider.boundary.isCloudLike)
    }

    @Test
    fun onDeviceGeneration_recordsSourceInTrace() = runTest {
        val provider = FirebaseAiLogicProvider(config, FakeRuntime(chunks = chunks(FirebaseAiSource.OnDevice, "He", "llo")))
        val result = InferenceStore.single(provider)
            .generate(InferenceRequest.text(key, "hi", privacy = PrivacyPolicy.publicData()))
        assertEquals("Hello", result.output)
        val modelId = result.trace?.attempts?.first()?.modelId
        assertTrue(modelId?.contains("OnDevice") == true, "trace should record the on-device source, was: $modelId")
    }

    @Test
    fun cloudGeneration_recordsSourceInTrace() = runTest {
        val provider = FirebaseAiLogicProvider(config, FakeRuntime(chunks = chunks(FirebaseAiSource.Cloud, "ok")))
        val result = InferenceStore.single(provider)
            .generate(InferenceRequest.text(key, "hi", privacy = PrivacyPolicy.publicData()))
        val completed = provider.stream(textRequest(), InferenceContext()).toList().last() as ProviderEvent.Completed<*>
        assertEquals(FirebaseAiSource.Cloud.name, completed.metadata.extra["firebase.source"])
        assertTrue(result.trace?.attempts?.first()?.modelId?.contains("Cloud") == true)
    }

    @Test
    fun cloudDeniedPrivacy_refusesHybrid() = runTest {
        // Default privacy denies cloud; the cloud-like hybrid is refused before any call.
        val provider = FirebaseAiLogicProvider(config, FakeRuntime(chunks = chunks(FirebaseAiSource.OnDevice, "nope")))
        val last = InferenceStore.single(provider).stream(InferenceRequest.text(key, "hi")).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.PolicyViolation, last.error.category)
    }

    @Test
    fun streamsTokensAndSourceMetadata() = runTest {
        val provider = FirebaseAiLogicProvider(config, FakeRuntime(chunks = chunks(FirebaseAiSource.OnDevice, "Hel", "lo")))
        val events = provider.stream(textRequest(), InferenceContext()).toList()
        assertTrue(events.first() is ProviderEvent.Started)
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ProviderEvent.Token>().map { it.text })
        val completed = events.last() as ProviderEvent.Completed<*>
        assertEquals("Hello", completed.output)
        assertEquals("gemini (OnDevice)", completed.metadata.modelId)
        assertEquals("firebase-ai-logic", completed.metadata.extra["runtime"])
    }

    @Test
    fun notConfigured_isUnavailable() = runTest {
        val provider = FirebaseAiLogicProvider(config, FakeRuntime(status = FirebaseAiStatus.Unavailable(FirebaseAiFailure.NotConfigured)))
        val availability = provider.availability(InferenceContext())
        assertTrue(availability is ProviderAvailability.Unavailable)
        assertEquals(UnavailableReason.Disabled, availability.reason)
    }

    @Test
    fun networkUnavailable_mapsToNetworkReason() = runTest {
        val provider = FirebaseAiLogicProvider(config, FakeRuntime(status = FirebaseAiStatus.Unavailable(FirebaseAiFailure.NetworkUnavailable)))
        val availability = provider.availability(InferenceContext()) as ProviderAvailability.Unavailable
        assertEquals(UnavailableReason.NetworkUnavailable, availability.reason)
    }

    @Test
    fun mappedGenerationError_keepsCategory() = runTest {
        val provider = FirebaseAiLogicProvider(config, FakeRuntime(error = FirebaseAiException(ErrorCategory.RateLimited, "429")))
        val last = provider.stream(textRequest(), InferenceContext()).toList().last()
        assertTrue(last is ProviderEvent.Failed)
        assertEquals(ErrorCategory.RateLimited, last.error.category)
    }

    @Test
    fun unmappedError_isTerminalUnknown() = runTest {
        val runtime = object : FirebaseAiRuntime {
            override suspend fun probe(config: FirebaseAiConfig) = FirebaseAiStatus.Ready
            override fun generate(config: FirebaseAiConfig, prompt: String): Flow<FirebaseAiChunk> =
                flow { throw IllegalStateException("boom") }
        }
        val last = FirebaseAiLogicProvider(config, runtime).stream(textRequest(), InferenceContext()).toList().last()
        assertTrue(last is ProviderEvent.Failed)
        assertEquals(ErrorCategory.Unknown, last.error.category)
    }

    @Test
    fun emptyGeneration_failsTransient() = runTest {
        val provider = FirebaseAiLogicProvider(config, FakeRuntime(chunks = emptyList()))
        val last = provider.stream(textRequest(), InferenceContext()).toList().last()
        assertTrue(last is ProviderEvent.Failed)
        assertEquals(ErrorCategory.TransientProviderError, last.error.category)
    }

    @Test
    fun nonTextOutput_isCapabilityUnsupported() = runTest {
        val provider = FirebaseAiLogicProvider(config, FakeRuntime())
        val customRequest = InferenceRequest(
            key = key,
            input = InferenceInput.Text("hi"),
            output = OutputSpec.Custom { it },
        ).toProviderRequest()
        val last = provider.stream(customRequest, InferenceContext()).toList().last()
        assertTrue(last is ProviderEvent.Failed)
        assertEquals(ErrorCategory.CapabilityUnsupported, last.error.category)
    }

    @Test
    fun cancellation_closesGeneration() = runTest {
        val runtime = FakeRuntime(block = true)
        val provider = FirebaseAiLogicProvider(config, runtime)
        val job = launch { provider.stream(textRequest(), InferenceContext()).toList() }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(runtime.cancelled)
    }

    @Test
    fun probeTimeout_isUnavailable() = runTest {
        val runtime = FakeRuntime(status = FirebaseAiStatus.Ready, probeDelay = 2.seconds)
        val availability = FirebaseAiLogicProvider(config, runtime).availability(InferenceContext()) as ProviderAvailability.Unavailable
        assertEquals(UnavailableReason.Unknown, availability.reason)
    }
}
