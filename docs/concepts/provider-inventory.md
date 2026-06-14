# Provider inventory

Foreground route planning is faster when provider availability/capability is already
known. A `ProviderInventory` caches that, refreshed in the **background** by a Meeseeks
worker so requests don't pay for live probes (`meeseeks-integration.md`).

- `ProviderInventoryRecord` — availability, unavailable reason, capabilities, and a
  `checkedAtMillis` timestamp for one provider.
- `ProviderInventory` — `get` / `put` / `all` / `clear`; `MemoryProviderInventory` is the
  in-memory implementation.
- `ProviderInventoryRefresher` — the Meeseeks-agnostic refresh logic: probe the requested
  providers' availability/capability (defensively — a throwing probe is recorded as
  unavailable) and write a record for each.

```kotlin
val inventory = MemoryProviderInventory()
val refresher = ProviderInventoryRefresher(providers, inventory)

// Probe the given providers and update the inventory:
refresher.refresh(RefreshProviderInventoryPayload(listOf("local", "cloud")), atEpochMillis = now())
inventory.get(ProviderId("local")) // ProviderInventoryRecord?
```

## Scheduling with Meeseeks

Wrap the refresher in a Meeseeks worker and schedule it periodically — the refresher is
the testable core; the worker is the thin scheduling shell:

```kotlin
class RefreshProviderInventoryWorker(
    private val refresher: ProviderInventoryRefresher,
    appContext: AppContext,
) : Worker<RefreshProviderInventoryPayload>(appContext) {
    override suspend fun run(payload: RefreshProviderInventoryPayload, context: RuntimeContext): TaskResult {
        refresher.refresh(payload, atEpochMillis = context.nowEpochMillis())
        return TaskResult.Success
    }
}

// Schedule a periodic refresh of your provider IDs (e.g. hourly).
meeseeks.schedule(
    payload = RefreshProviderInventoryPayload(listOf("local", "cloud")),
    period = 1.hours,
)
```

## Privacy

Refresh probes only call `availability()`/`capabilities()` — no inference, no prompts or
outputs. For a cloud provider, `availability()` may touch the network; only include
cloud provider IDs in the refresh set when that is acceptable for your deployment
(`meeseeks-integration.md`, open question 4).

Learn more: [storage model](../technical/storage-model.md),
[Meeseeks integration](../technical/meeseeks-integration.md).
