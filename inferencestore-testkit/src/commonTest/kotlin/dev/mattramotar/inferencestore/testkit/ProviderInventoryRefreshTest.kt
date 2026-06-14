package dev.mattramotar.inferencestore.testkit

import dev.mattramotar.inferencestore.core.inventory.MemoryProviderInventory
import dev.mattramotar.inferencestore.core.inventory.ProviderInventoryRefresher
import dev.mattramotar.inferencestore.core.inventory.RefreshProviderInventoryPayload
import dev.mattramotar.inferencestore.core.provider.Capability
import dev.mattramotar.inferencestore.core.provider.ProviderAvailability
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.ProviderKind
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Meeseeks provider-inventory refresh worker's core logic (OSS-35). */
class ProviderInventoryRefreshTest {

    private fun payload(vararg ids: String) = RefreshProviderInventoryPayload(ids.toList())

    @Test
    fun refresh_writesAvailabilityAndCapabilityRecords() = runTest {
        val available = fakeProvider("a", ProviderKind.Local) {}
        val unavailable = fakeProvider("b", ProviderKind.Cloud) {
            availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)
        }
        val inventory = MemoryProviderInventory()
        val refresher = ProviderInventoryRefresher(listOf(available, unavailable), inventory)

        refresher.refresh(payload("a", "b"), atEpochMillis = 1_000)

        val recordA = inventory.get(ProviderId("a"))!!
        assertTrue(recordA.available)
        assertTrue(Capability.TextGeneration in recordA.capabilities)
        assertEquals(1_000, recordA.checkedAtMillis)

        val recordB = inventory.get(ProviderId("b"))!!
        assertTrue(!recordB.available)
        assertEquals(UnavailableReason.ModelMissing, recordB.reason)
        assertTrue(recordB.capabilities.isEmpty()) // capabilities not probed when unavailable
    }

    @Test
    fun refresh_onlyTouchesRequestedProviders() = runTest {
        val a = fakeProvider("a", ProviderKind.Local) {}
        val b = fakeProvider("b", ProviderKind.Local) {}
        val inventory = MemoryProviderInventory()
        ProviderInventoryRefresher(listOf(a, b), inventory).refresh(payload("a"), atEpochMillis = 0)
        assertEquals(listOf(ProviderId("a")), inventory.all().map { it.providerId })
    }

    @Test
    fun refresh_unknownProviderId_isSkipped() = runTest {
        val a = fakeProvider("a", ProviderKind.Local) {}
        val inventory = MemoryProviderInventory()
        ProviderInventoryRefresher(listOf(a), inventory).refresh(payload("missing"), atEpochMillis = 0)
        assertTrue(inventory.all().isEmpty())
        assertNull(inventory.get(ProviderId("missing")))
    }

    @Test
    fun refresh_throwingProbe_recordsUnavailable() = runTest {
        val flaky = fakeProvider("flaky", ProviderKind.Local) { probeError = IllegalStateException("probe boom") }
        val inventory = MemoryProviderInventory()
        ProviderInventoryRefresher(listOf(flaky), inventory).refresh(payload("flaky"), atEpochMillis = 0)
        val record = inventory.get(ProviderId("flaky"))!!
        assertTrue(!record.available)
        assertEquals(UnavailableReason.Unknown, record.reason) // defensive: a throwing probe is "unavailable"
    }

    @Test
    fun refresh_throwingCapabilityProbe_recordsUnavailable() = runTest {
        // Availability succeeds, but the capability probe throws: we can't vouch for the
        // provider, so it must be recorded unavailable with no capabilities — not available.
        val flaky = fakeProvider("flaky", ProviderKind.Local) {
            capabilityProbeError = IllegalStateException("capability boom")
        }
        val inventory = MemoryProviderInventory()
        ProviderInventoryRefresher(listOf(flaky), inventory).refresh(payload("flaky"), atEpochMillis = 0)
        val record = inventory.get(ProviderId("flaky"))!!
        assertTrue(!record.available)
        assertEquals(UnavailableReason.Unknown, record.reason)
        assertTrue(record.capabilities.isEmpty())
    }
}
