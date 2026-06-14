# Telemetry upload

Apps that collect route telemetry want to batch and retry uploads without slowing
foreground inference. A `RouteTelemetryUploader` drains a buffer of redacted route
traces and ships them in the **background** (`meeseeks-integration.md`).

- `RouteTelemetryStore` — a buffer of pending [`RouteTrace`](../technical/event-model.md)s.
  Record one after each request; the worker drains batches. `MemoryRouteTelemetryStore`
  is the in-memory, bounded implementation.
- `TelemetryRecord` — the **redacted, uploadable projection** of a `RouteTrace`. A
  `RouteTrace` already holds only ids, categories, and timings — never prompts or
  outputs — so this type cannot carry user content by construction.
- `TelemetryUploader` — `suspend fun upload(batch): UploadOutcome`. You implement the
  network call to your analytics backend and classify the result (`Success` /
  `TransientFailure` / `PermanentFailure`).
- `RouteTelemetryUploader` — the Meeseeks-agnostic worker: gate on permission, project
  each trace through redaction, upload a batch, and remove uploaded records. Returns a
  `TelemetryUploadResult` (`Uploaded` / `Empty` / `PermissionDenied` / `RetryScheduled`
  / `Dropped`).

```kotlin
val store = MemoryRouteTelemetryStore()

// Foreground: record the redacted trace after each request (no prompt/output).
store.record(result.trace ?: return)

// Background: drain and upload, honoring the request's telemetry permission.
val worker = RouteTelemetryUploader(store, myUploader, privacy.telemetry)
val outcome = worker.upload(UploadTelemetryPayload(maxBatchSize = 100))
```

## Privacy

Telemetry is the most sensitive background task, so redaction is enforced in depth:

1. **Permission gate.** If `TelemetryPermission.emitMetrics` is off, the worker uploads
   nothing and returns `PermissionDenied` — no batch is even built. Pass the request's
   `privacy.telemetry` so a user who declined telemetry is never uploaded.
2. **Content-free by construction.** Only `RouteTrace`-derived `TelemetryRecord`s are
   uploaded. `RouteTrace` has no prompt or output fields, so raw content cannot leak
   into a batch even by mistake.
3. **Permission-filtered fields.** `emitProviderMetadata = false` drops provider and
   model ids; `emitHashes = false` drops even the **hashed** request key. The raw key
   is never emitted regardless — only a stable FNV-1a hash, and only when `emitHashes`
   is set.

`emitPrompt` / `emitOutput` exist on `TelemetryPermission` but have no effect here:
there is nothing in a route trace for them to opt into. Uploading prompts or outputs
would require a different, explicitly-built payload — out of scope for this worker.

## Reliability

`peek` is non-destructive, so the worker only removes records it has accounted for.
Each buffered trace gets a unique sequence id, and removal keys off that sequence —
not the trace's `requestId` (which is the request key string and so isn't unique). Two
requests against the same key are therefore distinct buffer entries; uploading one
never drops the other.

- **`Success`** → remove the uploaded records.
- **`TransientFailure`** (network blip, 5xx) → keep the batch; the next run retries it.
  An uploader that throws is treated as transient.
- **`PermanentFailure`** (malformed batch, 4xx) → drop the batch so one poison record
  can't block the queue forever.

`MemoryRouteTelemetryStore` is bounded (`maxRetained`, default 1000): telemetry is
best-effort, so when the buffer is full — e.g. uploads keep failing offline — the
oldest pending trace is dropped rather than growing memory without limit.

## Scheduling with Meeseeks

Wrap the uploader in a Meeseeks worker and schedule it periodically — the uploader is
the testable core; the worker is the thin scheduling shell:

```kotlin
class UploadTelemetryWorker(
    private val uploader: RouteTelemetryUploader,
    appContext: AppContext,
) : Worker<UploadTelemetryPayload>(appContext) {
    override suspend fun run(payload: UploadTelemetryPayload, context: RuntimeContext): TaskResult {
        return when (uploader.upload(payload).status) {
            TelemetryUploadStatus.RetryScheduled -> TaskResult.Retry // transient: back off and retry
            else -> TaskResult.Success                                // uploaded, empty, denied, or dropped
        }
    }
}

// Upload every 6 hours, only on a network, with backoff:
meeseeks.periodic(UploadTelemetryPayload(batchId = "default"), every = 6.hours) {
    requireNetwork()
    retryWithExponentialBackoff(initialDelay = 5.minutes, maxAttempts = 5)
}
```

Learn more: [monitor](monitor.md), [event model](../technical/event-model.md),
[Meeseeks integration](../technical/meeseeks-integration.md).
