package dev.mattramotar.inferencestore.provider.openai

import dev.mattramotar.inferencestore.core.InferenceStore
import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.policy.PrivacyPolicy
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.toProviderRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpenAiCompatibleProviderTest {

    private val key = InferenceKey("notes.summary", "n1")
    private fun textRequest() = InferenceRequest.text(key, "hi").toProviderRequest()

    private fun provider(
        client: HttpClient,
        supportsStructuredOutput: Boolean = false,
        online: suspend () -> Boolean = { true },
    ) = OpenAiCompatibleProvider(
        config = OpenAiConfig(
            baseUrl = "https://api.example.com/v1",
            model = "gpt-test",
            apiKey = { "test-key" },
            supportsStructuredOutput = supportsStructuredOutput,
        ),
        httpClient = client,
        online = online,
    )

    private fun sseClient(body: String): HttpClient = HttpClient(
        MockEngine { _ ->
            respond(
                content = ByteReadChannel(body.encodeToByteArray()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        },
    )

    @Test
    fun streaming_emitsTokensThenCompleted() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"content":"Hel"}}]}

            data: {"choices":[{"delta":{"content":"lo"}}]}

            data: [DONE]

        """.trimIndent()
        val events = provider(sseClient(sse)).stream(textRequest(), InferenceContext()).toList()

        assertTrue(events.first() is ProviderEvent.Started)
        val tokens = events.filterIsInstance<ProviderEvent.Token>().map { it.text }
        assertEquals(listOf("Hel", "lo"), tokens)
        val completed = events.last()
        assertTrue(completed is ProviderEvent.Completed<*>)
        assertEquals("Hello", completed.output)
        assertEquals("Hello", completed.rawText)
    }

    @Test
    fun streaming_parsesUsageWhenPresent() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"content":"ok"}}]}

            data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}

            data: [DONE]
        """.trimIndent()
        val completed = provider(sseClient(sse)).stream(textRequest(), InferenceContext())
            .toList().last() as ProviderEvent.Completed<*>
        assertEquals(4, completed.usage?.totalTokens)
    }

    @Test
    fun http401_mapsToPermanentError() = runTest {
        val client = HttpClient(MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) })
        val failed = provider(client).stream(textRequest(), InferenceContext()).toList().last()
        assertTrue(failed is ProviderEvent.Failed)
        assertEquals(ErrorCategory.PermanentProviderError, failed.error.category)
        // The raw body must not leak into the error message.
        assertTrue(failed.error.message?.contains("unauthorized") != true)
    }

    @Test
    fun http429_mapsToRateLimited_withRetryAfter() = runTest {
        val client = HttpClient(
            MockEngine {
                respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
            },
        )
        val failed = provider(client).stream(textRequest(), InferenceContext()).toList().last()
        assertTrue(failed is ProviderEvent.Failed)
        assertEquals(ErrorCategory.RateLimited, failed.error.category)
        assertEquals(2.seconds, failed.error.retryAfter)
    }

    @Test
    fun http500_mapsToTransient() = runTest {
        val client = HttpClient(MockEngine { respond("boom", HttpStatusCode.InternalServerError) })
        val failed = provider(client).stream(textRequest(), InferenceContext()).toList().last()
        assertTrue(failed is ProviderEvent.Failed)
        assertEquals(ErrorCategory.TransientProviderError, failed.error.category)
    }

    @Test
    fun apiKey_isSentAsBearerHeader() = runTest {
        var captured: HttpRequestData? = null
        val client = HttpClient(
            MockEngine { request ->
                captured = request
                respond(ByteReadChannel("data: [DONE]\n".encodeToByteArray()), HttpStatusCode.OK)
            },
        )
        provider(client).stream(textRequest(), InferenceContext()).toList()
        assertEquals("Bearer test-key", captured?.headers?.get(HttpHeaders.Authorization))
    }

    @Test
    fun availability_reflectsOnlineFlag() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        assertEquals(ProviderAvailability.Available, provider(client, online = { true }).availability(InferenceContext()))
        assertTrue(provider(client, online = { false }).availability(InferenceContext()) is ProviderAvailability.Unavailable)
    }

    @Test
    fun capabilities_reportUnsupportedStructuredOutput() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        val jsonRequest = InferenceRequest.json(key, "hi", serializer<String>())
        val report = provider(client, supportsStructuredOutput = false).capabilities(jsonRequest, InferenceContext())
        assertTrue(!report.supported)
    }

    @Test
    fun integratesWithInferenceStore_throughSingle() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"content":"world"}}]}

            data: [DONE]
        """.trimIndent()
        val result = InferenceStore.single(provider(sseClient(sse)))
            .generate(InferenceRequest.text(key, "hi", privacy = PrivacyPolicy.publicData()))
        assertEquals("world", result.output)
        assertEquals("openai-compatible", result.trace?.finalProvider)
    }
}
