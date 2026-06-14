package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.lifecycle.ProviderLifecycle
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.provider.CapabilityReport
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderEvent
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.ProviderPrivacyBoundary
import dev.mattramotar.inferencestore.core.provider.ProviderRequest
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A [FakeInferenceProvider] that also opts into [ProviderLifecycle], so warmup-worker
 * tests can exercise the supported path. Availability/capability/stream behavior comes
 * from the same [ProviderScript] DSL; [warmupError], when set, makes [warmup] throw.
 * Tracks warmup invocations and the last model warmed for assertions.
 */
@OptIn(ExperimentalAtomicApi::class)
public class FakeWarmableProvider(
    override val id: ProviderId,
    kind: ProviderKind = ProviderKind.Local,
    boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.localDevice(),
    script: ProviderScript,
    private val warmupError: Throwable? = null,
) : InferenceProvider, ProviderLifecycle {

    private val delegate = FakeInferenceProvider(id, kind, boundary, script)

    override val kind: ProviderKind get() = delegate.kind
    override val boundary: ProviderPrivacyBoundary get() = delegate.boundary

    override suspend fun availability(context: InferenceContext): ProviderAvailability =
        delegate.availability(context)

    override suspend fun capabilities(request: InferenceRequest<*>, context: InferenceContext): CapabilityReport =
        delegate.capabilities(request, context)

    override fun <Output : Any> stream(
        request: ProviderRequest<Output>,
        context: InferenceContext,
    ): Flow<ProviderEvent<Output>> = delegate.stream(request, context)

    // Atomic so concurrent warmups stay deterministic, matching FakeInferenceProvider's discipline.
    private val warmupCount = AtomicInt(0)
    private val lastModelId = AtomicReference<String?>(null)

    /** Number of times [warmup] was called. */
    public val warmupInvocations: Int get() = warmupCount.load()

    /** The `modelId` passed to the most recent [warmup] call, or `null`. */
    public val lastWarmedModelId: String? get() = lastModelId.load()

    override suspend fun warmup(modelId: String?, context: InferenceContext) {
        lastModelId.store(modelId)
        warmupCount.fetchAndAdd(1)
        warmupError?.let { throw it }
    }
}

/** Builds a [FakeWarmableProvider] from a [ProviderScript] DSL block. */
public fun fakeWarmableProvider(
    id: String,
    kind: ProviderKind = ProviderKind.Local,
    boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.localDevice(),
    warmupError: Throwable? = null,
    script: ProviderScript.() -> Unit = {},
): FakeWarmableProvider =
    FakeWarmableProvider(ProviderId(id), kind, boundary, ProviderScript().apply(script), warmupError)
