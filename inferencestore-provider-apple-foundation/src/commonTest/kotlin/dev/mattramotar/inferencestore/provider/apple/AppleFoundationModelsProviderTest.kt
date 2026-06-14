package dev.mattramotar.inferencestore.provider.apple

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.event.InferenceEvent
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.provider.Capability
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import dev.mattramotar.inferencestore.core.provider.toProviderRequest
import dev.mattramotar.inferencestore.testkit.fakeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Apple Foundation Models adapter (OSS-41), tested with a fake runtime — no SDK. */
class AppleFoundationModelsProviderTest {

    @Serializable
    private data class Summary(val text: String)

    private val config = AppleFoundationConfig()
    private val key = InferenceKey("apple", "test")
    private val context = InferenceContext()

    private class FakeAppleRuntime(
        private val availability: AppleModelAvailability = AppleModelAvailability.Available,
        private val tokens: List<String> = listOf("hello"),
        private val error: AppleFoundationException? = null,
    ) : AppleFoundationRuntime {
        var lastRequest: AppleGenerationRequest? = null
            private set

        override suspend fun availability(): AppleModelAvailability = availability

        override fun generate(request: AppleGenerationRequest): Flow<String> = flow {
            lastRequest = request
            error?.let { throw it }
            tokens.forEach { emit(it) }
        }
    }

    private fun provider(runtime: AppleFoundationRuntime) = AppleFoundationModelsProvider(config, runtime)

    @Test
    fun providerIdentity_isPlatformWithAppleBoundary() {
        val p = provider(FakeAppleRuntime())
        assertEquals(ProviderKind.Platform, p.kind)
        assertEquals("apple", p.boundary.vendor)
        assertTrue(!p.boundary.isCloudLike) // on-device: no cloud permission required
    }

    @Test
    fun capabilities_reportStreamingAndStructuredOutput() = runTest {
        val report = provider(FakeAppleRuntime()).capabilities(InferenceRequest.text(key, "hi"), context)
        assertTrue(Capability.Streaming in report.capabilities)
        assertTrue(Capability.StructuredOutput in report.capabilities)
        assertTrue(Capability.Offline in report.capabilities)
    }

    @Test
    fun availability_available_mapsToAvailable() = runTest {
        val a = provider(FakeAppleRuntime(availability = AppleModelAvailability.Available)).availability(context)
        assertEquals(ProviderAvailability.Available, a)
    }

    @Test
    fun availability_unavailability_mapsToCanonicalReason() = runTest {
        suspend fun reasonFor(u: AppleModelUnavailability): UnavailableReason {
            val a = provider(FakeAppleRuntime(availability = AppleModelAvailability.Unavailable(u))).availability(context)
            return (a as ProviderAvailability.Unavailable).reason
        }
        assertEquals(UnavailableReason.Unsupported, reasonFor(AppleModelUnavailability.OsTooOld))
        assertEquals(UnavailableReason.Unsupported, reasonFor(AppleModelUnavailability.DeviceNotEligible))
        assertEquals(UnavailableReason.Disabled, reasonFor(AppleModelUnavailability.AppleIntelligenceNotEnabled))
        assertEquals(UnavailableReason.ModelMissing, reasonFor(AppleModelUnavailability.ModelNotReady))
        assertEquals(UnavailableReason.Unknown, reasonFor(AppleModelUnavailability.Unknown))
    }

    @Test
    fun stream_textRequest_streamsTokensThenCompletes() = runTest {
        val runtime = FakeAppleRuntime(tokens = listOf("Hel", "lo"))
        val events = provider(runtime).stream(InferenceRequest.text(key, "hi").toProviderRequest(), context).toList()

        assertTrue(events.first() is ProviderEvent.Started)
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ProviderEvent.Token>().map { it.text })
        val completed = events.last() as ProviderEvent.Completed<*>
        assertEquals("Hello", completed.output)
        // Free text is not a guided-generation request.
        assertEquals(false, runtime.lastRequest?.structured)
    }

    @Test
    fun stream_jsonRequest_requestsGuidedGeneration_andParsesTypedOutput() = runTest {
        val runtime = FakeAppleRuntime(tokens = listOf("{\"text\":", "\"hi\"}"))
        val request = InferenceRequest.json(key, "summarize", Summary.serializer()).toProviderRequest()

        val events = provider(runtime).stream(request, context).toList()

        // Guided generation requested, with the schema name as a hint.
        assertEquals(true, runtime.lastRequest?.structured)
        assertEquals(Summary.serializer().descriptor.serialName, runtime.lastRequest?.schemaName)
        val completed = events.last() as ProviderEvent.Completed<*>
        assertEquals(Summary("hi"), completed.output)
    }

    @Test
    fun stream_malformedJson_failsWithParsingFailed() = runTest {
        val runtime = FakeAppleRuntime(tokens = listOf("not json"))
        val request = InferenceRequest.json(key, "summarize", Summary.serializer()).toProviderRequest()
        val failed = provider(runtime).stream(request, context).toList().last() as ProviderEvent.Failed
        assertEquals(ErrorCategory.ParsingFailed, failed.error.category)
    }

    @Test
    fun stream_emptyGeneration_failsTransient() = runTest {
        val runtime = FakeAppleRuntime(tokens = emptyList())
        val failed = provider(runtime).stream(InferenceRequest.text(key, "hi").toProviderRequest(), context).toList().last() as ProviderEvent.Failed
        assertEquals(ErrorCategory.TransientProviderError, failed.error.category)
    }

    @Test
    fun stream_runtimeThrowsMappedError_isFailedWithThatCategory() = runTest {
        val runtime = FakeAppleRuntime(error = AppleFoundationException(ErrorCategory.PermanentProviderError, message = "boom"))
        val failed = provider(runtime).stream(InferenceRequest.text(key, "hi").toProviderRequest(), context).toList().last() as ProviderEvent.Failed
        assertEquals(ErrorCategory.PermanentProviderError, failed.error.category)
    }

    @Test
    fun unavailable_throughStore_isProviderUnavailable() = runTest {
        val unavailable = provider(FakeAppleRuntime(availability = AppleModelAvailability.Unavailable(AppleModelUnavailability.DeviceNotEligible)))
        val last = InferenceStore.single(unavailable).stream(InferenceRequest.text(key, "hi")).toList().last()
        assertTrue(last is InferenceEvent.Failed)
        assertEquals(ErrorCategory.ProviderUnavailable, last.error.category)
    }

    @Test
    fun unavailable_fallsBackToCloud() = runTest {
        val appleUnavailable = provider(FakeAppleRuntime(availability = AppleModelAvailability.Unavailable(AppleModelUnavailability.AppleIntelligenceNotEnabled)))
        val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("from cloud") }
        val store = InferenceStore.build {
            provider(appleUnavailable)
            provider(cloud)
        }

        // Cloud must be permitted for the fallback to actually reach it.
        val request = InferenceRequest.text(key, "hi", privacy = PrivacyPolicy.publicData())
        val last = store.stream(request).toList().last()

        assertTrue(last is InferenceEvent.Done)
        assertEquals("from cloud", last.result.output)
        assertEquals("cloud", last.result.trace?.finalProvider)
    }
}
