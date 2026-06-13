package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.model.InferenceRequest
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** One scripted step a [FakeInferenceProvider] replays on collection. */
internal sealed interface ScriptStep {
    data class Token(val text: String) : ScriptStep
    data class Complete(val text: String) : ScriptStep
    data class Fail(val error: ProviderError) : ScriptStep
    data class Delay(val duration: Duration) : ScriptStep
    data object BlockUntilCancelled : ScriptStep
}

/**
 * Declarative behavior for a [FakeInferenceProvider]: availability, capabilities,
 * and an ordered list of stream steps. Lets route/fallback/cancellation tests run
 * without a real model or network.
 */
public class ProviderScript {
    public var availability: ProviderAvailability = ProviderAvailability.Available
    public var modelId: String? = null
    public val capabilities: MutableSet<Capability> =
        mutableSetOf(Capability.TextGeneration, Capability.Streaming)

    internal val steps: MutableList<ScriptStep> = mutableListOf()

    /** Restrict the supported capabilities to exactly [caps]. */
    public fun supports(vararg caps: Capability) {
        capabilities.clear()
        capabilities.addAll(caps)
    }

    /** Stream [tokens] in order. */
    public fun tokens(vararg tokens: String) {
        tokens.forEach { steps += ScriptStep.Token(it) }
    }

    /** Terminate the attempt successfully with [text]. */
    public fun complete(text: String) {
        steps += ScriptStep.Complete(text)
    }

    /** Terminate the attempt with a provider failure. */
    public fun fail(error: ProviderError) {
        steps += ScriptStep.Fail(error)
    }

    /** Convenience: fail with a stable [category]. */
    public fun fail(category: ErrorCategory, message: String? = null) {
        steps += ScriptStep.Fail(ProviderError(category, message))
    }

    /** Suspend for [duration] (uses the test virtual clock under `runTest`). */
    public fun delay(duration: Duration) {
        steps += ScriptStep.Delay(duration)
    }

    /** Suspend until the collector cancels — for cancellation tests. */
    public fun blockUntilCancelled() {
        steps += ScriptStep.BlockUntilCancelled
    }
}

/**
 * A deterministic [InferenceProvider] driven by a [ProviderScript]. Tracks how
 * many times it was invoked and whether it was cancelled, so tests can assert
 * route decisions and privacy guarantees without a real model.
 */
public class FakeInferenceProvider(
    override val id: ProviderId,
    override val kind: ProviderKind = ProviderKind.Test,
    override val boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.localDevice(),
    private val script: ProviderScript,
) : InferenceProvider {

    private var invocationCount: Int = 0
    private var cancelledFlag: Boolean = false

    /** Number of times [stream] was collected. */
    public val invocations: Int get() = invocationCount

    /** Whether a collection was cancelled mid-stream. */
    public val wasCancelled: Boolean get() = cancelledFlag

    override suspend fun availability(context: InferenceContext): ProviderAvailability = script.availability

    override suspend fun capabilities(
        request: InferenceRequest<*>,
        context: InferenceContext,
    ): CapabilityReport = CapabilityReport(
        supported = request.requiredCapabilities().all { it in script.capabilities },
        capabilities = script.capabilities.toSet(),
    )

    override fun <Output : Any> stream(
        request: ProviderRequest<Output>,
        context: InferenceContext,
    ): Flow<ProviderEvent<Output>> = flow {
        invocationCount++
        val metadata = ProviderMetadata(
            providerId = id,
            providerKind = kind,
            boundary = boundary,
            modelId = script.modelId,
            capabilities = script.capabilities.toSet(),
        )
        emit(ProviderEvent.Started(metadata))
        try {
            for (step in script.steps) {
                when (step) {
                    is ScriptStep.Token -> emit(ProviderEvent.Token(step.text))
                    is ScriptStep.Delay -> delay(step.duration)
                    is ScriptStep.BlockUntilCancelled -> awaitCancellation()
                    is ScriptStep.Fail -> {
                        emit(ProviderEvent.Failed(step.error))
                        return@flow
                    }
                    is ScriptStep.Complete -> {
                        @Suppress("UNCHECKED_CAST")
                        emit(ProviderEvent.Completed(step.text as Output, rawText = step.text, metadata = metadata))
                        return@flow
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            cancelledFlag = true
            throw cancellation
        }
    }

    /** Fails unless this provider was invoked exactly [expected] times. */
    public fun assertInvocations(expected: Int) {
        if (invocations != expected) {
            throw AssertionError("Provider '${id.value}' expected $expected invocation(s) but had $invocations")
        }
    }

    /** Fails unless a collection was cancelled. */
    public fun assertCancelled() {
        if (!cancelledFlag) {
            throw AssertionError("Provider '${id.value}' was expected to be cancelled but was not")
        }
    }
}

/** Builds a [FakeInferenceProvider] from a [ProviderScript] DSL block. */
public fun fakeProvider(
    id: String,
    kind: ProviderKind = ProviderKind.Test,
    boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.localDevice(),
    script: ProviderScript.() -> Unit,
): FakeInferenceProvider =
    FakeInferenceProvider(ProviderId(id), kind, boundary, ProviderScript().apply(script))
