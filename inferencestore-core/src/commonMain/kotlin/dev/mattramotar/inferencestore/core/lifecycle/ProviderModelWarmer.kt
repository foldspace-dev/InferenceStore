package dev.mattramotar.inferencestore.core.lifecycle

import dev.mattramotar.inferencestore.core.inventory.ProviderInventory
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderId
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** Task payload for a background model warmup (`meeseeks-integration.md`). */
@Serializable
public data class WarmupModelPayload(
    public val providerId: String,
    public val modelId: String? = null,
)

/** Why a [WarmupResult] ended the way it did. */
public enum class WarmupStatus {
    /** [ProviderLifecycle.warmup] completed without throwing. */
    Warmed,

    /** The requested provider id is not registered with the warmer. */
    NotFound,

    /** The provider does not implement [ProviderLifecycle]; warmup is optional. */
    Unsupported,

    /** Precondition not met: the provider is not currently available. */
    SkippedUnavailable,

    /** [ProviderLifecycle.warmup] threw; the failure is recorded, not propagated. */
    Failed,
}

/** Outcome of one warmup attempt — a record the Meeseeks worker maps to a `TaskResult`. */
public data class WarmupResult(
    public val providerId: ProviderId,
    public val modelId: String?,
    public val status: WarmupStatus,
    public val warmedAtMillis: Long,
)

/**
 * Warms a provider's model before expected use — the Meeseeks-agnostic core of the
 * warmup worker (OSS-38). Wrap [warmup] in a Meeseeks `Worker<WarmupModelPayload>`
 * (see the model-warmup docs) to run it after launch / on a schedule; tests call it
 * directly with fake providers.
 *
 * Warmup is optional: a provider that does not implement [ProviderLifecycle] is
 * recorded [WarmupStatus.Unsupported] and never touched. Before warming, the worker
 * checks a precondition — the provider must be available — preferring a cached
 * [inventory] record (from the inventory refresher, OSS-35) over a live probe so it
 * doesn't pay for a probe the refresher already did. A warmup that throws is caught
 * and recorded [WarmupStatus.Failed]; one bad warmup never crashes the worker.
 */
public class ProviderModelWarmer(
    private val providers: List<InferenceProvider>,
    private val inventory: ProviderInventory? = null,
) {
    /** Warm the [payload]'s provider/model and return the recorded outcome. */
    public suspend fun warmup(
        payload: WarmupModelPayload,
        atEpochMillis: Long,
        context: InferenceContext = InferenceContext(),
    ): WarmupResult {
        val providerId = ProviderId(payload.providerId)

        fun result(status: WarmupStatus) =
            WarmupResult(providerId, payload.modelId, status, atEpochMillis)

        val provider = providers.firstOrNull { it.id == providerId }
            ?: return result(WarmupStatus.NotFound)

        val lifecycle = provider as? ProviderLifecycle
            ?: return result(WarmupStatus.Unsupported)

        if (!isAvailable(provider, atEpochMillis, context)) {
            return result(WarmupStatus.SkippedUnavailable)
        }

        return try {
            lifecycle.warmup(payload.modelId, context)
            result(WarmupStatus.Warmed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            result(WarmupStatus.Failed)
        }
    }

    /**
     * Precondition check: prefer a fresh cached [ProviderInventory] record over a
     * live probe. A throwing live probe is treated as "not available".
     */
    private suspend fun isAvailable(
        provider: InferenceProvider,
        atEpochMillis: Long,
        context: InferenceContext,
    ): Boolean {
        inventory?.get(provider.id)?.let { record ->
            val expired = record.expiresAtMillis?.let { atEpochMillis >= it } ?: false
            if (!expired) return record.available
        }
        return try {
            provider.availability(context) == ProviderAvailability.Available
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            false
        }
    }
}
