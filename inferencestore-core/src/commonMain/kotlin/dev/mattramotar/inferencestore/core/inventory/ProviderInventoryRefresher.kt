package dev.mattramotar.inferencestore.core.inventory

import dev.mattramotar.inferencestore.core.model.InferenceKey
import dev.mattramotar.inferencestore.core.model.InferenceRequest
import dev.mattramotar.inferencestore.core.provider.InferenceContext
import dev.mattramotar.inferencestore.core.provider.InferenceProvider
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** Task payload for a background inventory refresh (`meeseeks-integration.md`). */
@Serializable
public data class RefreshProviderInventoryPayload(public val providerIds: List<String>)

/**
 * Refreshes [ProviderInventory] by probing provider availability/capability — the
 * Meeseeks-agnostic core of the inventory worker (OSS-35). Wrap [refresh] in a Meeseeks
 * `Worker<RefreshProviderInventoryPayload>` (see the route-journal/inventory docs) to run
 * it on a schedule; tests call it directly with fake providers.
 *
 * Probes are defensive: a provider that throws is recorded as unavailable, so one bad
 * provider never fails the whole refresh.
 */
public class ProviderInventoryRefresher(
    private val providers: List<InferenceProvider>,
    private val inventory: ProviderInventory,
) {
    private val probeRequest: InferenceRequest<String> = InferenceRequest.text(InferenceKey("inventory", "probe"), "")

    /** Probe the [payload]'s providers and write a record for each found provider. */
    public suspend fun refresh(
        payload: RefreshProviderInventoryPayload,
        atEpochMillis: Long,
        context: InferenceContext = InferenceContext(),
    ): List<ProviderInventoryRecord> {
        val requested = payload.providerIds.mapTo(mutableSetOf()) { ProviderId(it) }
        val targets = providers.filter { it.id in requested }
        return targets.map { provider ->
            val availability = probe(context) { provider.availability(it) }.orUnavailable
            val available = availability == ProviderAvailability.Available
            val capabilities = if (available) {
                probe(context) { provider.capabilities(probeRequest, it).capabilities } ?: emptySet()
            } else {
                emptySet()
            }
            val record = ProviderInventoryRecord(
                providerId = provider.id,
                available = available,
                reason = (availability as? ProviderAvailability.Unavailable)?.reason,
                capabilities = capabilities,
                modelId = null, // model identity is known only at stream time
                checkedAtMillis = atEpochMillis,
            )
            inventory.put(record)
            record
        }
    }

    // A throwing probe is treated as unavailable/empty rather than failing the refresh.
    private suspend fun <T> probe(context: InferenceContext, block: suspend (InferenceContext) -> T): T? =
        try {
            block(context)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            null
        }
}

// A null availability probe (threw) is treated as unavailable for an unknown reason.
private val ProviderAvailability?.orUnavailable: ProviderAvailability
    get() = this ?: ProviderAvailability.Unavailable(UnavailableReason.Unknown)
