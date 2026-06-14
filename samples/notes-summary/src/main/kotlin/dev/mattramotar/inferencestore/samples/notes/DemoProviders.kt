package dev.mattramotar.inferencestore.samples.notes

import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.provider.litertlm.LiteRtLmBackend
import dev.mattramotar.inferencestore.provider.litertlm.LiteRtLmProvider
import dev.mattramotar.inferencestore.provider.litertlm.LiteRtLmProviderConfig
import dev.mattramotar.inferencestore.provider.openai.OpenAiCompatibleProvider
import dev.mattramotar.inferencestore.provider.openai.OpenAiConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

/**
 * An OpenAI-compatible provider backed by a mock HTTP engine that returns a canned
 * streamed completion, so the sample exercises the real adapter (request building +
 * SSE parsing) fully offline. Swap the [HttpClient] engine for ktor-client-cio /
 * okhttp to call a live endpoint. [summary] must contain no `"` or `\` (kept simple
 * so the hand-written SSE body stays valid JSON).
 */
public fun openAiCompatibleDemoProvider(
    summary: String = "Cloud summary: Q3 roadmap on track, demo Friday.",
): InferenceProvider {
    val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"$summary\"}}]}\n\ndata: [DONE]\n\n"
    val client = HttpClient(
        MockEngine {
            respond(
                content = ByteReadChannel(sse.encodeToByteArray()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        },
    )
    return OpenAiCompatibleProvider(
        config = OpenAiConfig(baseUrl = "https://api.example.com/v1", model = "gpt-demo", apiKey = { "demo-key" }),
        httpClient = client,
    )
}

/** The LiteRT-LM provider wired to the bundled [DemoLiteRtLmRuntime] for a model path. */
public fun liteRtLmDemoProvider(modelPath: String): LiteRtLmProvider = LiteRtLmProvider(
    config = LiteRtLmProviderConfig(modelPath = modelPath, modelId = "demo-gemma", backend = LiteRtLmBackend.Auto),
    runtime = DemoLiteRtLmRuntime(),
)
