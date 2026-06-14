package dev.mattramotar.inferencestore.core.inventory

import dev.mattramotar.inferencestore.core.provider.Capability
import dev.mattramotar.inferencestore.core.provider.ProviderId
import dev.mattramotar.inferencestore.core.provider.UnavailableReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A cached availability/capability snapshot for one provider (`storage-model.md`),
 * written by a background refresh so foreground routing can skip live probes.
 */
public data class ProviderInventoryRecord(
    public val providerId: ProviderId,
    public val available: Boolean,
    public val reason: UnavailableReason?,
    public val capabilities: Set<Capability>,
    public val modelId: String?,
    public val checkedAtMillis: Long,
    public val expiresAtMillis: Long? = null,
)

/** Stores known provider availability/capability. Interface + in-memory impl in MVP. */
public interface ProviderInventory {
    public suspend fun get(providerId: ProviderId): ProviderInventoryRecord?
    public suspend fun put(record: ProviderInventoryRecord)
    public suspend fun all(): List<ProviderInventoryRecord>
    public suspend fun clear()
}

/** In-memory [ProviderInventory]; coroutine-safe via a [Mutex]. */
public class MemoryProviderInventory : ProviderInventory {
    private val mutex = Mutex()
    private val records: MutableMap<ProviderId, ProviderInventoryRecord> = mutableMapOf()

    override suspend fun get(providerId: ProviderId): ProviderInventoryRecord? =
        mutex.withLock { records[providerId] }

    override suspend fun put(record: ProviderInventoryRecord) {
        mutex.withLock { records[record.providerId] = record }
    }

    override suspend fun all(): List<ProviderInventoryRecord> = mutex.withLock { records.values.toList() }

    override suspend fun clear() {
        mutex.withLock { records.clear() }
    }
}
