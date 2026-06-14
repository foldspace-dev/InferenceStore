package dev.mattramotar.inferencestore.provider.openai

import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.provider.Capability
import dev.mattramotar.inferencestore.core.provider.CapabilityReport
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.ErrorSource
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
import dev.mattramotar.inferencestore.core.provider.Usage
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import dev.mattramotar.inferencestore.core.provider.requiredCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * An [InferenceProvider] for any OpenAI-compatible `/chat/completions` endpoint
 * (OSS-24, `provider-adapters.md`).
 *
 * Engine-agnostic: the caller supplies a configured [HttpClient] (with their
 * platform engine), so this module forces no Ktor engine. Streaming is parsed
 * from the SSE response; provider/HTTP failures are mapped to stable
 * [ErrorCategory] values and never carry the raw response body or API key.
 */
public class OpenAiCompatibleProvider(
    private val config: OpenAiConfig,
    private val httpClient: HttpClient,
    override val id: ProviderId = ProviderId(ID),
    private val online: suspend () -> Boolean = { true },
) : InferenceProvider {

    override val kind: ProviderKind = ProviderKind.Cloud
    override val boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.thirdPartyCloud(config.vendor)

    private val capabilities: Set<Capability> = buildSet {
        add(Capability.TextGeneration)
        add(Capability.Chat)
        add(Capability.Streaming)
        if (config.supportsStructuredOutput) add(Capability.StructuredOutput)
    }

    override suspend fun availability(context: InferenceContext): ProviderAvailability =
        if (online()) ProviderAvailability.Available else ProviderAvailability.Unavailable(UnavailableReason.NetworkUnavailable)

    override suspend fun capabilities(request: InferenceRequest<*>, context: InferenceContext): CapabilityReport =
        CapabilityReport(
            supported = request.requiredCapabilities().all { it in capabilities },
            capabilities = capabilities,
        )

    override fun <Output : Any> stream(
        request: ProviderRequest<Output>,
        context: InferenceContext,
    ): Flow<ProviderEvent<Output>> = flow {
        val metadata = ProviderMetadata(
            providerId = id,
            providerKind = kind,
            boundary = boundary,
            modelId = config.model,
            capabilities = capabilities,
        )
        emit(ProviderEvent.Started(metadata))

        val body = json.encodeToString(ChatCompletionRequest.serializer(), requestBody(request))
        val apiKey = config.apiKey()

        try {
            val response = httpClient.post(config.chatCompletionsUrl) {
                contentType(ContentType.Application.Json)
                if (apiKey != null) header(HttpHeaders.Authorization, "Bearer $apiKey")
                config.organization?.let { header("OpenAI-Organization", it) }
                setBody(body)
            }
            if (!response.status.isSuccess()) {
                emit(ProviderEvent.Failed(mapHttpError(response)))
                return@flow
            }
            val accumulated = StringBuilder()
            var usage: Usage? = null
            // Parse the SSE body and emit tokens. MVP reads the body as text for
            // cross-platform reliability (Ktor 3 kotlinx-io channels differ on
            // Native); socket-level streaming via the Ktor SSE plugin is a follow-up.
            for (line in response.bodyAsText().lineSequence()) {
                if (!line.startsWith(DATA_PREFIX)) continue
                val data = line.removePrefix(DATA_PREFIX).trim()
                if (data == DONE) break
                val chunk = runCatching { json.decodeFromString(StreamChunk.serializer(), data) }.getOrNull() ?: continue
                chunk.usage?.let { usage = it.toUsage() }
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    accumulated.append(delta)
                    emit(ProviderEvent.Token(delta))
                }
            }
            val rawText = accumulated.toString()
            when (val parsed = parseOutput(request.output, rawText)) {
                is ParseResult.Ok -> emit(ProviderEvent.Completed(parsed.output, rawText = rawText, usage = usage, metadata = metadata))
                is ParseResult.Error -> emit(ProviderEvent.Failed(parsed.error))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            emit(ProviderEvent.Failed(mapException(throwable)))
        }
    }

    private fun <Output : Any> requestBody(request: ProviderRequest<Output>): ChatCompletionRequest {
        val messages = when (val input = request.input) {
            is InferenceInput.Text -> listOf(WireMessage("user", input.value))
            is InferenceInput.Messages -> input.messages.map { WireMessage(it.role.name.lowercase(), it.content) }
        }
        val responseFormat = if (config.supportsStructuredOutput && request.output is OutputSpec.Json) {
            ResponseFormat("json_object")
        } else {
            null
        }
        return ChatCompletionRequest(model = config.model, messages = messages, stream = true, responseFormat = responseFormat)
    }

    private fun <Output : Any> parseOutput(output: OutputSpec<Output>, rawText: String): ParseResult<Output> = try {
        @Suppress("UNCHECKED_CAST")
        when (output) {
            is OutputSpec.Text -> ParseResult.Ok(rawText as Output)
            is OutputSpec.Json -> ParseResult.Ok(json.decodeFromString(output.serializer, rawText))
            is OutputSpec.Custom -> ParseResult.Ok(output.parser.parse(rawText))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        ParseResult.Error(ProviderError(ErrorCategory.ParsingFailed, message = "failed to parse output", cause = throwable))
    }

    private suspend fun mapHttpError(response: HttpResponse): ProviderError {
        val code = response.status.value
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
        val (category, source) = when {
            code == 401 || code == 403 -> ErrorCategory.PermanentProviderError to ErrorSource.RequestInvalid
            code == 408 -> ErrorCategory.Timeout to ErrorSource.AttemptTimeout
            code == 429 -> ErrorCategory.RateLimited to null
            code == 504 -> ErrorCategory.Timeout to ErrorSource.AttemptTimeout
            code in 500..599 -> ErrorCategory.TransientProviderError to ErrorSource.ProviderSpecific
            code in 400..499 -> ErrorCategory.PermanentProviderError to ErrorSource.RequestInvalid
            else -> ErrorCategory.Unknown to null
        }
        // The raw body is intentionally not read into the message — it may carry secrets/PII.
        return ProviderError(category, message = "HTTP $code", retryAfter = retryAfter, source = source)
    }

    private fun mapException(throwable: Throwable): ProviderError {
        val name = throwable::class.simpleName.orEmpty()
        return when {
            name.contains("Timeout", ignoreCase = true) ->
                ProviderError(ErrorCategory.Timeout, source = ErrorSource.AttemptTimeout, cause = throwable)
            else ->
                // Cloud transport failures are treated as transient so routing can fall back.
                ProviderError(ErrorCategory.TransientProviderError, source = ErrorSource.ProviderSpecific, cause = throwable)
        }
    }

    private sealed interface ParseResult<out Output : Any> {
        data class Ok<Output : Any>(val output: Output) : ParseResult<Output>
        data class Error(val error: ProviderError) : ParseResult<Nothing>
    }

    public companion object {
        public const val ID: String = "openai-compatible"
        private const val DATA_PREFIX: String = "data:"
        private const val DONE: String = "[DONE]"
        private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

private fun WireUsage.toUsage(): Usage =
    Usage(inputTokens = promptTokens, outputTokens = completionTokens, totalTokens = totalTokens)
