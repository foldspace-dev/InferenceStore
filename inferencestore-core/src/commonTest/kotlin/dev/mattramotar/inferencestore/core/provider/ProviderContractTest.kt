package dev.mattramotar.inferencestore.core.provider

import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderContractTest {

    private val key = InferenceKey("notes.summary", "n1")

    /** Minimal in-test provider; the real fakes/testkit arrive in OSS-12. */
    private val echo = object : InferenceProvider {
        override val id = ProviderId("echo")
        override val kind = ProviderKind.Test
        override val boundary = ProviderPrivacyBoundary.localDevice()

        override suspend fun availability(context: InferenceContext) = ProviderAvailability.Available

        override suspend fun capabilities(
            request: InferenceRequest<*>,
            context: InferenceContext,
        ): CapabilityReport {
            val caps = setOf<Capability>(Capability.TextGeneration, Capability.Streaming)
            return CapabilityReport(
                supported = request.requiredCapabilities().all { it in caps },
                capabilities = caps,
            )
        }

        override fun <Output : Any> stream(
            request: ProviderRequest<Output>,
            context: InferenceContext,
        ): Flow<ProviderEvent<Output>> = flow {
            val meta = ProviderMetadata(id, kind, boundary, modelId = "echo-1")
            emit(ProviderEvent.Started(meta))
            val textInput = request.input as? InferenceInput.Text
            if (textInput == null) {
                emit(
                    ProviderEvent.Failed(
                        ProviderError(
                            category = ErrorCategory.CapabilityUnsupported,
                            message = "echo test provider supports only text input",
                        ),
                    ),
                )
                return@flow
            }
            if (request.output !is OutputSpec.Text) {
                emit(
                    ProviderEvent.Failed(
                        ProviderError(
                            category = ErrorCategory.CapabilityUnsupported,
                            message = "echo test provider supports only text output",
                        ),
                    ),
                )
                return@flow
            }
            val text = textInput.value
            emit(ProviderEvent.Token(text))
            @Suppress("UNCHECKED_CAST")
            emit(ProviderEvent.Completed(text as Output, rawText = text, metadata = meta))
        }
    }

    @Test
    fun availabilityAndBoundary() = runTest {
        assertEquals(ProviderAvailability.Available, echo.availability(InferenceContext()))
        assertTrue(echo.boundary.isLocal)
        assertEquals(ProviderKind.Test, echo.kind)
    }

    @Test
    fun capabilities_supportTextRequest() = runTest {
        val report = echo.capabilities(InferenceRequest.text(key, "hi"), InferenceContext())
        assertTrue(report.supported)
        assertTrue(Capability.Streaming in report.capabilities)
    }

    @Test
    fun stream_emitsStartedTokenCompleted() = runTest {
        val req = InferenceRequest.text(key, "hello").toProviderRequest()
        val events = echo.stream(req, InferenceContext()).toList()
        assertEquals(3, events.size)
        assertTrue(events.first() is ProviderEvent.Started)
        assertTrue(events[1] is ProviderEvent.Token)
        val done = events.last()
        assertTrue(done is ProviderEvent.Completed)
        assertEquals("hello", done.output)
    }

    @Test
    fun requiredCapabilities_includeChatForMessages() {
        val messages = InferenceRequest(
            key = key,
            input = InferenceInput.Messages(emptyList()),
            output = OutputSpec.Text,
        )
        assertTrue(Capability.Chat in messages.requiredCapabilities())
    }
}
