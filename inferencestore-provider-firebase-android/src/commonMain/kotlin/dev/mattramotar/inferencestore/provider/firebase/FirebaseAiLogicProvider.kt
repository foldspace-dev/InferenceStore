package dev.mattramotar.inferencestore.provider.firebase

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
import kotlin.coroutines.cancellation.CancellationException

/**
 * A **hybrid** [InferenceProvider] over Firebase AI Logic, which itself decides
 * between on-device (Gemini Nano) and cloud (Gemini) per request (OSS-32 prototype).
 *
 * Design: a single convenience provider rather than two logical providers, because
 * Firebase owns the on-device/cloud routing. The trade-off is privacy modeling — see
 * [boundary] below; apps needing a hard local-only guarantee should use a dedicated
 * on-device provider instead.
 *
 * Runtime-agnostic: takes a [FirebaseAiRuntime] integrators implement with the SDK, so
 * the adapter is fully testable. The actual source (on-device vs cloud) of each
 * generation is reported in the completion metadata's `modelId` and `extra`, so the
 * route trace records which path Firebase used. Maturity: experimental.
 */
public class FirebaseAiLogicProvider(
    private val config: FirebaseAiConfig,
    private val runtime: FirebaseAiRuntime,
) : InferenceProvider {

    override val id: ProviderId = config.providerId
    override val kind: ProviderKind = ProviderKind.Platform

    /**
     * A cloud-like boundary (`platformHybrid`): because the hybrid MAY use cloud, the
     * privacy gate must treat it conservatively — a request that denies cloud is never
     * routed here, even if this particular call would have stayed on-device. The
     * boundary reflects the worst case, not the per-request source.
     */
    override val boundary: ProviderPrivacyBoundary =
        ProviderPrivacyBoundary.platformHybrid("Firebase AI Logic / Google")

    // No Offline: the hybrid may require network for the cloud path.
    private val capabilities: Set<Capability> =
        setOf(Capability.TextGeneration, Capability.Chat, Capability.Streaming, Capability.StructuredOutput)

    override suspend fun availability(context: InferenceContext): ProviderAvailability {
        val budget = listOfNotNull(context.timeout.availabilityTimeout, config.initializationTimeout).minOrNull()
        val status = if (budget != null) {
            withTimeoutOrNull(budget) { runtime.probe(config) } ?: FirebaseAiStatus.Unavailable(FirebaseAiFailure.InitializationTimeout)
        } else {
            runtime.probe(config)
        }
        return when (status) {
            FirebaseAiStatus.Ready -> ProviderAvailability.Available
            is FirebaseAiStatus.Unavailable -> ProviderAvailability.Unavailable(status.failure.toReason())
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
        emit(ProviderEvent.Started(metadata(source = null)))

        if (request.output !is OutputSpec.Text) {
            emit(ProviderEvent.Failed(ProviderError(ErrorCategory.CapabilityUnsupported, message = "this prototype produces text output only")))
            return@flow
        }

        val prompt = promptOf(request.input)
        val accumulated = StringBuilder()
        var source: FirebaseAiSource? = null
        try {
            runtime.generate(config, prompt).collect { chunk ->
                source = chunk.source
                accumulated.append(chunk.text)
                emit(ProviderEvent.Token(chunk.text))
            }
            val text = accumulated.toString()
            if (text.isEmpty()) {
                emit(ProviderEvent.Failed(ProviderError(ErrorCategory.TransientProviderError, message = "empty response", source = ErrorSource.ProviderSpecific)))
                return@flow
            }
            // Completion metadata carries the source Firebase actually used, so the
            // route trace's modelId records on-device vs cloud.
            @Suppress("UNCHECKED_CAST")
            emit(ProviderEvent.Completed(text as Output, rawText = text, metadata = metadata(source)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: FirebaseAiException) {
            emit(ProviderEvent.Failed(ProviderError(error.category, message = error.message, cause = error.cause, source = error.source)))
        } catch (throwable: Throwable) {
            emit(ProviderEvent.Failed(ProviderError(ErrorCategory.Unknown, cause = throwable)))
        }
    }

    private fun metadata(source: FirebaseAiSource?): ProviderMetadata = ProviderMetadata(
        providerId = id,
        providerKind = kind,
        boundary = boundary,
        // modelId encodes the actual source so it lands in the route trace; null until known.
        modelId = source?.let { "${config.modelId} (${it.name})" },
        capabilities = capabilities,
        extra = buildMap {
            put("runtime", "firebase-ai-logic")
            source?.let { put("firebase.source", it.name) }
        },
    )

    private fun promptOf(input: InferenceInput): String = when (input) {
        is InferenceInput.Text -> input.value
        is InferenceInput.Messages -> input.messages.joinToString("\n") { "${it.role.name}: ${it.content}" }
    }

    private fun FirebaseAiFailure.toReason(): UnavailableReason = when (this) {
        FirebaseAiFailure.NotConfigured -> UnavailableReason.Disabled
        FirebaseAiFailure.NetworkUnavailable -> UnavailableReason.NetworkUnavailable
        FirebaseAiFailure.OnDeviceModelMissing -> UnavailableReason.ModelMissing
        FirebaseAiFailure.QuotaExceeded,
        FirebaseAiFailure.InitializationTimeout,
        FirebaseAiFailure.Unknown,
        -> UnavailableReason.Unknown
    }

    public companion object {
        public const val ID: String = "firebase-ai-logic"
    }
}
