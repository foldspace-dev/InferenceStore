# Model warmup

Local models can have high first-token latency until they are loaded and warmed.
A `ProviderModelWarmer` warms a model in the **background**, ahead of expected use,
so the first foreground request doesn't pay that cost (`meeseeks-integration.md`).

- `ProviderLifecycle` — an optional interface a provider implements to support
  lifecycle hooks. It declares `suspend fun warmup(modelId, context)`. Support is
  opt-in: a provider that doesn't implement it is simply skipped.
- `WarmupModelPayload` — `providerId` and an optional `modelId` (`null` warms the
  provider's default model).
- `ProviderModelWarmer` — the Meeseeks-agnostic warmup logic: resolve the provider,
  check a precondition (it must be available), call `warmup`, and return a
  `WarmupResult`. A throwing warmup is caught and recorded — never propagated.
- `WarmupResult` / `WarmupStatus` — the recorded outcome: `Warmed`, `NotFound`
  (provider not registered), `Unsupported` (no `ProviderLifecycle`),
  `SkippedUnavailable` (precondition not met), or `Failed` (warmup threw).

```kotlin
val warmer = ProviderModelWarmer(providers, inventory) // inventory is optional

val result = warmer.warmup(WarmupModelPayload("local", "small-summarizer"), atEpochMillis = now())
when (result.status) {
    WarmupStatus.Warmed -> { /* ready; first request is fast */ }
    WarmupStatus.SkippedUnavailable -> { /* model not present yet — schedule a download */ }
    WarmupStatus.Failed -> { /* retry later */ }
    else -> Unit
}
```

## Preconditions

Warming a model that isn't even available is wasted work, so the warmer checks
availability first. When a [`ProviderInventory`](provider-inventory.md) is supplied,
it prefers a **fresh** cached record (one whose `expiresAtMillis` hasn't passed) over
a live probe — reusing what the inventory refresher already learned. With no usable
cached record it falls back to a live `availability()` probe; a throwing probe is
treated as "not available" and the warmup is skipped rather than crashing.

Device-level preconditions — battery acceptable, not thermally constrained, app
recently launched — belong on the Meeseeks schedule (see below), not in the worker.

## Scheduling with Meeseeks

Wrap the warmer in a Meeseeks worker and schedule it after launch — the warmer is the
testable core; the worker is the thin scheduling shell:

```kotlin
class WarmupModelWorker(
    private val warmer: ProviderModelWarmer,
    appContext: AppContext,
) : Worker<WarmupModelPayload>(appContext) {
    override suspend fun run(payload: WarmupModelPayload, context: RuntimeContext): TaskResult {
        val result = warmer.warmup(payload, atEpochMillis = context.nowEpochMillis())
        return when (result.status) {
            WarmupStatus.Failed -> TaskResult.Retry      // transient: try again later
            else -> TaskResult.Success                   // warmed, skipped, or not applicable
        }
    }
}

// Warm shortly after launch, only when the battery is healthy:
meeseeks.oneTime(WarmupModelPayload("local", "small-summarizer")) {
    initialDelay(30.seconds)
    requireBatteryNotLow(true)
}
```

## Privacy

Warmup loads model weights and runs no real prompt or output — it carries no user
content. For a provider whose `availability()` touches the network, the precondition
probe may too; only warm cloud-capable providers when that is acceptable for your
deployment (`meeseeks-integration.md`, open question 4).

Learn more: [provider inventory](provider-inventory.md),
[Meeseeks integration](../technical/meeseeks-integration.md).
