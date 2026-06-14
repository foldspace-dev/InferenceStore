package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.inventory.MemoryProviderInventory
import dev.mattramotar.inferencestore.core.inventory.ProviderInventoryRecord
import dev.mattramotar.inferencestore.core.lifecycle.ProviderModelWarmer
import dev.mattramotar.inferencestore.core.lifecycle.WarmupModelPayload
import dev.mattramotar.inferencestore.core.lifecycle.WarmupStatus
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Meeseeks model-warmup worker's core logic (OSS-38). */
class ProviderModelWarmupTest {

    private fun payload(id: String, modelId: String? = null) = WarmupModelPayload(id, modelId)

    @Test
    fun warmup_availableLifecycleProvider_isWarmed() = runTest {
        val provider = fakeWarmableProvider("local")
        val warmer = ProviderModelWarmer(listOf(provider))

        val result = warmer.warmup(payload("local", "summarizer"), atEpochMillis = 1_000)

        assertEquals(WarmupStatus.Warmed, result.status)
        assertEquals("summarizer", result.modelId)
        assertEquals(1_000, result.warmedAtMillis)
        assertEquals(1, provider.warmupInvocations)
        assertEquals("summarizer", provider.lastWarmedModelId)
    }

    @Test
    fun warmup_providerWithoutLifecycle_isUnsupported() = runTest {
        // A plain provider does not implement ProviderLifecycle: warmup is optional.
        val provider = fakeProvider("local", ProviderKind.Local) {}
        val warmer = ProviderModelWarmer(listOf(provider))

        val result = warmer.warmup(payload("local"), atEpochMillis = 0)

        assertEquals(WarmupStatus.Unsupported, result.status)
    }

    @Test
    fun warmup_unknownProviderId_isNotFound() = runTest {
        val warmer = ProviderModelWarmer(listOf(fakeWarmableProvider("local")))
        val result = warmer.warmup(payload("missing"), atEpochMillis = 0)
        assertEquals(WarmupStatus.NotFound, result.status)
    }

    @Test
    fun warmup_unavailableProvider_isSkipped_withoutWarming() = runTest {
        val provider = fakeWarmableProvider("local") {
            availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)
        }
        val warmer = ProviderModelWarmer(listOf(provider))

        val result = warmer.warmup(payload("local"), atEpochMillis = 0)

        assertEquals(WarmupStatus.SkippedUnavailable, result.status)
        assertEquals(0, provider.warmupInvocations) // precondition checked before warming
    }

    @Test
    fun warmup_throwingWarmup_recordsFailed_withoutCrashing() = runTest {
        val provider = fakeWarmableProvider("local", warmupError = IllegalStateException("warmup boom"))
        val warmer = ProviderModelWarmer(listOf(provider))

        val result = warmer.warmup(payload("local"), atEpochMillis = 42)

        assertEquals(WarmupStatus.Failed, result.status)
        assertEquals(1, provider.warmupInvocations) // it was attempted, then failed
        assertEquals(42, result.warmedAtMillis)
    }

    @Test
    fun warmup_throwingAvailabilityProbe_isSkipped() = runTest {
        // A throwing precondition probe means "not available" — defensively skip, don't crash.
        val provider = fakeWarmableProvider("local") { probeError = IllegalStateException("probe boom") }
        val warmer = ProviderModelWarmer(listOf(provider))

        val result = warmer.warmup(payload("local"), atEpochMillis = 0)

        assertEquals(WarmupStatus.SkippedUnavailable, result.status)
        assertEquals(0, provider.warmupInvocations)
    }

    @Test
    fun warmup_prefersCachedInventoryOverLiveProbe() = runTest {
        // Live availability would throw, but a fresh inventory record says available:
        // the cached record wins, so we warm without paying for a live probe.
        val provider = fakeWarmableProvider("local") { probeError = IllegalStateException("probe boom") }
        val inventory = MemoryProviderInventory()
        inventory.put(
            ProviderInventoryRecord(
                providerId = ProviderId("local"),
                available = true,
                reason = null,
                capabilities = emptySet(),
                modelId = null,
                checkedAtMillis = 0,
            ),
        )
        val warmer = ProviderModelWarmer(listOf(provider), inventory)

        val result = warmer.warmup(payload("local"), atEpochMillis = 10)

        assertEquals(WarmupStatus.Warmed, result.status)
        assertEquals(1, provider.warmupInvocations)
    }

    @Test
    fun warmup_unavailableInventoryRecord_skipsWithoutWarming() = runTest {
        // Inventory says unavailable: skip even though the live provider would be available.
        val provider = fakeWarmableProvider("local")
        val inventory = MemoryProviderInventory()
        inventory.put(
            ProviderInventoryRecord(
                providerId = ProviderId("local"),
                available = false,
                reason = UnavailableReason.ModelMissing,
                capabilities = emptySet(),
                modelId = null,
                checkedAtMillis = 0,
            ),
        )
        val warmer = ProviderModelWarmer(listOf(provider), inventory)

        val result = warmer.warmup(payload("local"), atEpochMillis = 10)

        assertEquals(WarmupStatus.SkippedUnavailable, result.status)
        assertEquals(0, provider.warmupInvocations)
    }

    @Test
    fun warmup_expiredInventoryRecord_fallsBackToLiveProbe() = runTest {
        // An expired record is ignored; the live probe (available) decides instead.
        val provider = fakeWarmableProvider("local")
        val inventory = MemoryProviderInventory()
        inventory.put(
            ProviderInventoryRecord(
                providerId = ProviderId("local"),
                available = false,
                reason = UnavailableReason.ModelMissing,
                capabilities = emptySet(),
                modelId = null,
                checkedAtMillis = 0,
                expiresAtMillis = 5,
            ),
        )
        val warmer = ProviderModelWarmer(listOf(provider), inventory)

        val result = warmer.warmup(payload("local"), atEpochMillis = 10) // 10 >= 5 -> expired

        assertEquals(WarmupStatus.Warmed, result.status)
        assertEquals(1, provider.warmupInvocations)
    }

    @Test
    fun warmup_inventoryRecord_expiresAtExactBoundary_fallsBackToLiveProbe() = runTest {
        // At exactly expiresAtMillis the record is expired (>=), so the live probe decides.
        val provider = fakeWarmableProvider("local")
        val inventory = MemoryProviderInventory()
        inventory.put(
            ProviderInventoryRecord(
                providerId = ProviderId("local"),
                available = false,
                reason = UnavailableReason.ModelMissing,
                capabilities = emptySet(),
                modelId = null,
                checkedAtMillis = 0,
                expiresAtMillis = 10,
            ),
        )
        val warmer = ProviderModelWarmer(listOf(provider), inventory)

        val result = warmer.warmup(payload("local"), atEpochMillis = 10) // 10 >= 10 -> expired

        assertEquals(WarmupStatus.Warmed, result.status)
        assertEquals(1, provider.warmupInvocations)
    }

    @Test
    fun warmup_nullModelId_warmsDefaultModel() = runTest {
        val provider = fakeWarmableProvider("local")
        val warmer = ProviderModelWarmer(listOf(provider))

        val result = warmer.warmup(payload("local", modelId = null), atEpochMillis = 0)

        assertEquals(WarmupStatus.Warmed, result.status)
        assertNull(result.modelId)
        assertNull(provider.lastWarmedModelId)
    }
}
