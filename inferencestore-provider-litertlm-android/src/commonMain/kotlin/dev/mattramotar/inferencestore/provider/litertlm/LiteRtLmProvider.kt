package dev.mattramotar.inferencestore.provider.litertlm

import dev.mattramotar.inferencestore.core.model.InferenceInput
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.provider.Capability
import dev.mattramotar.inferencestore.core.provider.CapabilityReport
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderError
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.provider.ProviderExecutionBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderMetadata
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundaryId
import dev.mattramotar.inferencestore.core.provider.ProviderRequest
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import dev.mattramotar.inferencestore.core.provider.requiredCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * A local, on-device [InferenceProvider] backed by LiteRT-LM (Google AI Edge),
 * the MVP's first real local adapter (OSS-29, `litert-lm-adapter.md`).
 *
 * The provider is runtime-agnostic: it takes a [LiteRtLmRuntime] that integrators
 * implement with the native library, so availability/capability/error mapping,
 * streaming, and the local privacy boundary are real and fully testable. Engine
 * initialization and native work happen inside the runtime, off the UI dispatcher.
 */
public class LiteRtLmProvider(
    private val config: LiteRtLmProviderConfig,
    private val runtime: LiteRtLmRuntime,
) : InferenceProvider {

    override val id: ProviderId = config.providerId
    override val kind: ProviderKind = ProviderKind.Local
    override val boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary(
        id = ProviderPrivacyBoundaryId("litertlm-local"),
        execution = ProviderExecutionBoundary.LocalProcess,
        vendor = "Google AI Edge / LiteRT-LM",
    )

    private val capabilities: Set<Capability> =
        setOf(Capability.TextGeneration, Capability.Chat, Capability.Streaming, Capability.Offline)

    override suspend fun availability(context: InferenceContext): ProviderAvailability {
        // Bound the probe by the smaller of the request availability timeout and the
        // adapter's init timeout; a timed-out probe is treated as unavailable.
        val budget = listOfNotNull(context.timeout.availabilityTimeout, config.initializationTimeout).minOrNull()
        val status = if (budget != null) {
            withTimeoutOrNull(budget) { runtime.probe(config.modelPath, config.backend) }
                ?: LiteRtLmStatus.Unavailable(LiteRtLmFailure.InitializationTimeout)
        } else {
            runtime.probe(config.modelPath, config.backend)
        }
        return when (status) {
            LiteRtLmStatus.Ready -> ProviderAvailability.Available
            is LiteRtLmStatus.Unavailable -> ProviderAvailability.Unavailable(status.failure.toReason())
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
            runtimeVersion = "litert-lm",
            capabilities = capabilities,
            extra = mapOf("backend" to config.backend.name),
        )
        emit(ProviderEvent.Started(metadata))

        val prompt = promptOf(request.input)
        val accumulated = StringBuilder()
        try {
            runtime.generate(config.modelPath, config.backend, prompt).collect { token ->
                accumulated.append(token)
                emit(ProviderEvent.Token(token))
            }
            val text = accumulated.toString()
            @Suppress("UNCHECKED_CAST")
            emit(ProviderEvent.Completed(text as Output, rawText = text, metadata = metadata))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: LiteRtLmException) {
            emit(ProviderEvent.Failed(ProviderError(error.category, message = error.message, cause = error.cause)))
        } catch (throwable: Throwable) {
            // Unmapped native failures are terminal (Unknown) per "improve mapping
            // before enabling fallback" — adapters should map known errors explicitly.
            emit(ProviderEvent.Failed(ProviderError(ErrorCategory.Unknown, cause = throwable)))
        }
    }

    private fun promptOf(input: InferenceInput): String = when (input) {
        is InferenceInput.Text -> input.value
        is InferenceInput.Messages -> input.messages.joinToString("\n") { "${it.role.name}: ${it.content}" }
    }

    private fun LiteRtLmFailure.toReason(): UnavailableReason = when (this) {
        LiteRtLmFailure.MissingModel, LiteRtLmFailure.ModelUnreadable -> UnavailableReason.ModelMissing
        LiteRtLmFailure.UnsupportedBackend -> UnavailableReason.Unsupported
        LiteRtLmFailure.InitializationTimeout,
        LiteRtLmFailure.InsufficientMemory,
        LiteRtLmFailure.RuntimeInitializationFailed,
        -> UnavailableReason.Unknown
    }

    public companion object {
        public const val ID: String = "litertlm-local"
    }
}
