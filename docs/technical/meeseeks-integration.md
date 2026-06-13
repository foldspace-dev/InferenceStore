# Meeseeks integration

Generated: 2026-06-13

## Purpose

Meeseeks should power background work around inference, not the foreground inference API itself.

Hybrid inference needs background tasks for model lifecycle, precomputation, and reliability. Meeseeks already provides KMP scheduling and persistence across Android, JVM, JS, and iOS, making it a natural companion module.

## Module

```text
inferencestore-meeseeks
```

Depends on:

- `inferencestore-core`
- `dev.mattramotar.meeseeks:runtime`
- optional storage module

## Background task categories

### 1. Model availability refresh

Periodically checks provider/model availability.

Use cases:

- local model downloaded?
- platform model available?
- runtime initialized?
- cloud provider reachable?
- rate limit cooldown expired?

### 2. Model download

Schedules model download when conditions are favorable.

Preconditions:

- network required
- Wi-Fi preferred
- charging preferred for large models
- battery not low
- storage available

### 3. Model warmup

Warms local model before expected use.

Preconditions:

- app recently launched
- user likely to use feature
- device not thermally constrained
- battery acceptable

### 4. Model pruning

Removes old models/artifacts.

Preconditions:

- storage pressure
- model unused for N days
- superseded by newer version

### 5. Deferred inference

Runs lower-priority inference later.

Use cases:

- summarizing saved articles
- extracting tasks from backlog
- generating embeddings
- offline-first queued work

### 6. Telemetry upload

Uploads redacted route traces and metrics.

Preconditions:

- network
- privacy policy allows upload
- batch threshold reached

### 7. Retry/redrive

Retries failed transient inference tasks.

Preconditions:

- provider cooldown expired
- network restored
- model available

## Task payloads

```kotlin
@Serializable
data class RefreshProviderInventoryPayload(
    val providerIds: List<String>
) : TaskPayload

@Serializable
data class DownloadModelPayload(
    val providerId: String,
    val modelId: String
) : TaskPayload

@Serializable
data class WarmupModelPayload(
    val providerId: String,
    val modelId: String
) : TaskPayload

@Serializable
data class PruneModelsPayload(
    val maxUnusedAgeDays: Int
) : TaskPayload

@Serializable
data class DeferredInferencePayload(
    val requestId: String,
    val fingerprint: String
) : TaskPayload

@Serializable
data class UploadTelemetryPayload(
    val batchId: String
) : TaskPayload
```

## Worker sketch

```kotlin
class RefreshProviderInventoryWorker(
    private val providers: ProviderRegistry,
    private val inventory: ProviderInventory,
    appContext: AppContext
) : Worker<RefreshProviderInventoryPayload>(appContext) {

    override suspend fun run(
        payload: RefreshProviderInventoryPayload,
        context: RuntimeContext
    ): TaskResult {
        payload.providerIds.forEach { id ->
            val provider = providers.get(ProviderId(id)) ?: return@forEach
            val availability = provider.availability(context.toInferenceContext())
            inventory.put(provider.toInventoryRecord(availability))
        }
        return TaskResult.Success
    }
}
```

## Scheduling examples

### Download on Wi-Fi

```kotlin
meeseeks.oneTime(DownloadModelPayload("litertlm", "gemma-4-e2b")) {
    requireNetwork()
    requireCharging(true)
    requireBatteryNotLow(true)
    retryWithExponentialBackoff(initialDelay = 10.minutes, maxAttempts = 3)
}
```

### Warmup after launch

```kotlin
meeseeks.oneTime(WarmupModelPayload("local", "small-summarizer")) {
    initialDelay(30.seconds)
    requireBatteryNotLow(true)
}
```

### Periodic telemetry upload

```kotlin
meeseeks.periodic(UploadTelemetryPayload(batchId = "default"), every = 6.hours) {
    requireNetwork()
    retryWithExponentialBackoff(initialDelay = 5.minutes, maxAttempts = 5)
}
```

## Integration architecture

```text
InferenceStore Core
   |
   +--> ProviderInventory
   +--> RouteJournal
   +--> ArtifactStore
   |
InferenceStore Meeseeks Module
   |
   +--> Workers
   +--> Task payload serializers
   +--> Scheduling helpers
   +--> Lifecycle policies
```

## Foreground/background boundary

Foreground request should not depend on Meeseeks.

Instead:

- foreground reads provider inventory if available
- foreground can enqueue follow-up tasks
- background refreshes inventory and model assets
- background does not make route decisions for foreground requests unless running deferred inference

## Meeseeks as differentiator

This is where the combination of your projects is strongest:

- Store-like policy and source-of-truth semantics
- Meeseeks task scheduling and retry semantics
- KMP common code
- offline-first AI lifecycle

## MVP recommendation

Do not include Meeseeks in MVP core.

Create RFC and issue backlog, then build after core alpha.

## First Meeseeks feature

Provider inventory refresh is the safest first integration:

- small scope
- no model downloads
- useful to routing
- easy to test

## Later features

- model download policies
- warmup scheduling
- telemetry batching
- deferred inference queue
- artifact pruning
- background embeddings

## Open questions

1. Should Meeseeks tasks be generated automatically or app-scheduled? Recommendation: helper APIs, app controls scheduling.
2. Should model downloads be provider-specific? Recommendation: yes, through provider lifecycle interface.
3. Should deferred inference use same `InferenceRequest` serialization? Recommendation: yes, if privacy allows persistence.
4. Should background workers have cloud access? Recommendation: only if request privacy policy allows it.
