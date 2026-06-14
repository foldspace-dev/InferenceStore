package dev.mattramotar.inferencestore.provider.apple

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
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import dev.mattramotar.inferencestore.core.provider.requiredCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * A local, on-device [InferenceProvider] backed by Apple's `FoundationModels`
 * framework — InferenceStore's first iOS platform adapter (OSS-41, `adr/0006`).
 * **Experimental** until validated on a device.
 *
 * The provider is SDK-agnostic: it takes an [AppleFoundationRuntime] that integrators
 * implement with a Swift shim, so availability gating, streaming, guided-generation
 * (structured output), error mapping, and the on-device privacy boundary are real and
 * fully testable without the framework. The on-device model needs no network and no API
 * key, so this provider reports a `platform("apple")` boundary. (Apple's separate
 * Private Cloud Compute path must be modeled as a distinct cloud-like provider, not this
 * one.)
 */
public class AppleFoundationModelsProvider(
    private val config: AppleFoundationConfig,
    private val runtime: AppleFoundationRuntime,
) : InferenceProvider {

    override val id: ProviderId = config.providerId
    override val kind: ProviderKind = ProviderKind.Platform
    override val boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.platform("apple")

    private val json: Json = Json { ignoreUnknownKeys = true }

    private val capabilities: Set<Capability> = setOf(
        Capability.TextGeneration,
        Capability.Chat,
        Capability.Streaming,
        Capability.StructuredOutput,
        Capability.Offline,
    )

    override suspend fun availability(context: InferenceContext): ProviderAvailability {
        // Bound the probe by the smaller of the request availability timeout and the
        // adapter's own; a timed-out probe is treated as unavailable.
        val budget = listOfNotNull(context.timeout.availabilityTimeout, config.availabilityTimeout).minOrNull()
        val status = if (budget != null) {
            withTimeoutOrNull(budget) { runtime.availability() }
                ?: AppleModelAvailability.Unavailable(AppleModelUnavailability.Unknown)
        } else {
            runtime.availability()
        }
        return when (status) {
            AppleModelAvailability.Available -> ProviderAvailability.Available
            is AppleModelAvailability.Unavailable -> ProviderAvailability.Unavailable(status.reason.toReason())
        }
    }

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
            modelId = config.modelId,
            capabilities = capabilities,
            extra = mapOf("runtime" to "apple-foundation-models", "maturity" to "experimental"),
        )
        emit(ProviderEvent.Started(metadata))

        val structured = request.output is OutputSpec.Json || request.output is OutputSpec.Custom
        val generation = AppleGenerationRequest(
            prompt = promptOf(request.input),
            structured = structured,
            schemaName = schemaNameOf(request.output),
        )

        val accumulated = StringBuilder()
        try {
            runtime.generate(generation).collect { token ->
                accumulated.append(token)
                emit(ProviderEvent.Token(token))
            }
            val rawText = accumulated.toString()
            if (rawText.isEmpty()) {
                // An empty on-device generation is a failure so routing can fall back.
                emit(ProviderEvent.Failed(ProviderError(ErrorCategory.TransientProviderError, message = "empty response", source = ErrorSource.ProviderSpecific)))
                return@flow
            }
            when (val parsed = parseOutput(request.output, rawText)) {
                is ParseResult.Ok -> emit(ProviderEvent.Completed(parsed.output, rawText = rawText, metadata = metadata))
                is ParseResult.Error -> emit(ProviderEvent.Failed(parsed.error))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: AppleFoundationException) {
            emit(ProviderEvent.Failed(ProviderError(error.category, message = error.message, cause = error.cause, source = error.source)))
        } catch (throwable: Throwable) {
            // Unmapped framework failures are terminal (Unknown); the runtime should map
            // known errors to AppleFoundationException explicitly.
            emit(ProviderEvent.Failed(ProviderError(ErrorCategory.Unknown, cause = throwable)))
        }
    }

    private fun promptOf(input: InferenceInput): String = when (input) {
        is InferenceInput.Text -> input.value
        is InferenceInput.Messages -> input.messages.joinToString("\n") { "${it.role.name}: ${it.content}" }
    }

    // For JSON output, pass the schema's serial name as a hint for the runtime's
    // @Generable guided-generation type; free-text/custom outputs carry no schema name.
    private fun schemaNameOf(output: OutputSpec<*>): String? = when (output) {
        is OutputSpec.Json -> output.serializer.descriptor.serialName
        else -> null
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
        // The throwable can wrap the unparseable model output; keep only a static message
        // and do not attach it as cause, to avoid retaining raw content.
        ParseResult.Error(ProviderError(ErrorCategory.ParsingFailed, message = "failed to parse output"))
    }

    private fun AppleModelUnavailability.toReason(): UnavailableReason = when (this) {
        AppleModelUnavailability.OsTooOld, AppleModelUnavailability.DeviceNotEligible -> UnavailableReason.Unsupported
        AppleModelUnavailability.AppleIntelligenceNotEnabled -> UnavailableReason.Disabled
        AppleModelUnavailability.ModelNotReady -> UnavailableReason.ModelMissing
        AppleModelUnavailability.Unknown -> UnavailableReason.Unknown
    }

    private sealed interface ParseResult<out Output : Any> {
        data class Ok<Output : Any>(val output: Output) : ParseResult<Output>
        data class Error(val error: ProviderError) : ParseResult<Nothing>
    }

    public companion object {
        public const val ID: String = "apple-foundation-system"
    }
}
