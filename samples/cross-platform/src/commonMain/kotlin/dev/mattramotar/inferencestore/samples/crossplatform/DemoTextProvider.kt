package dev.mattramotar.inferencestore.samples.crossplatform

import dev.mattramotar.inferencestore.core.model.OutputSpec
import dev.mattramotar.inferencestore.core.provider.Capability
import dev.mattramotar.inferencestore.core.provider.CapabilityReport
import dev.mattramotar.inferencestore.core.provider.ErrorCategory
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
import dev.mattramotar.inferencestore.core.provider.requiredCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A tiny common-code [InferenceProvider] that streams a canned [summary]. It stands in
 * for a real platform adapter so the sample runs everywhere with no model or SDK — the
 * platform `actual`s wire one of these tagged per platform; production code swaps in the
 * LiteRT-LM (Android) / Apple Foundation Models (iOS) adapters.
 */
public class DemoTextProvider(
    providerId: String,
    override val kind: ProviderKind,
    private val summary: String,
    override val boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.localDevice(),
) : InferenceProvider {

    override val id: ProviderId = ProviderId(providerId)
    private val capabilities: Set<Capability> = setOf(Capability.TextGeneration, Capability.Streaming)

    override suspend fun availability(context: InferenceContext): ProviderAvailability = ProviderAvailability.Available

    override suspend fun capabilities(
        request: dev.mattramotar.inferencestore.core.model.InferenceRequest<*>,
        context: InferenceContext,
    ): CapabilityReport = CapabilityReport(
        supported = request.requiredCapabilities().all { it in capabilities },
        capabilities = capabilities,
    )

    override fun <Output : Any> stream(
        request: ProviderRequest<Output>,
        context: InferenceContext,
    ): Flow<ProviderEvent<Output>> = flow {
        val metadata = ProviderMetadata(id, kind, boundary, modelId = "${id.value}-demo", capabilities = capabilities)
        emit(ProviderEvent.Started(metadata))
        if (request.output !is OutputSpec.Text) {
            emit(ProviderEvent.Failed(ProviderError(ErrorCategory.CapabilityUnsupported, message = "demo produces text only")))
            return@flow
        }
        val mid = summary.length / 2
        emit(ProviderEvent.Token(summary.substring(0, mid)))
        emit(ProviderEvent.Token(summary.substring(mid)))
        @Suppress("UNCHECKED_CAST")
        emit(ProviderEvent.Completed(summary as Output, rawText = summary, metadata = metadata))
    }
}
