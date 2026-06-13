# Canonical event model

Updated: 2026-06-13

This document is the source of truth for stream events, route traces, and monitor event projection. Other docs should not invent parallel event taxonomies.

## Principles

- Events are ordered and testable.
- Stream events are user-facing and may include content tokens.
- Monitor events are redacted projections of the same lifecycle.
- Route traces are the durable summary of the event lifecycle.

## Canonical stream events

```kotlin
sealed interface InferenceEvent<out Output : Any> {
    data class Started(val requestId: RequestId, val key: InferenceKey) : InferenceEvent<Nothing>
    data class CacheChecked(val requestId: RequestId, val outcome: CacheOutcome) : InferenceEvent<Nothing>
    data class ProvidersEvaluated(val requestId: RequestId, val candidates: List<ProviderCandidateSummary>) : InferenceEvent<Nothing>
    data class RouteSelected(val requestId: RequestId, val route: InferenceRouteSummary) : InferenceEvent<Nothing>
    data class ProviderAttemptStarted(val requestId: RequestId, val attempt: ProviderAttemptSummary) : InferenceEvent<Nothing>
    data class Token(val requestId: RequestId, val text: String) : InferenceEvent<Nothing>
    data class Partial<Output : Any>(val requestId: RequestId, val value: Output) : InferenceEvent<Output>
    data class ValidationCompleted(val requestId: RequestId, val result: ValidationResult) : InferenceEvent<Nothing>
    data class ProviderAttemptCompleted(val requestId: RequestId, val attempt: ProviderAttemptSummary) : InferenceEvent<Nothing>
    data class FallbackStarted(val requestId: RequestId, val reason: FallbackReason, val next: ProviderId?) : InferenceEvent<Nothing>
    data class ArtifactStored(val requestId: RequestId, val outcome: ArtifactWriteOutcome) : InferenceEvent<Nothing>
    data class Done<Output : Any>(val requestId: RequestId, val result: InferenceResult<Output>) : InferenceEvent<Output>
    data class Failed(val requestId: RequestId, val error: InferenceError, val trace: RouteTrace) : InferenceEvent<Nothing>
}
```

`Cancelled` is represented as `Failed(error.category = Cancelled)` unless Kotlin Flow cancellation prevents terminal emission to the cancelling collector. Monitor/trace must still record cancellation when observable.

## Successful single-provider order

```text
Started
CacheChecked
ProvidersEvaluated
RouteSelected
ProviderAttemptStarted
Token*
Partial*
ValidationCompleted(pass)
ProviderAttemptCompleted(success)
ArtifactStored?          // only if cache/artifact write allowed
Done
```

## Cache-hit order

```text
Started
CacheChecked(hit)
ValidationCompleted(pass)?
Done
```

## Fallback order

```text
Started
CacheChecked
ProvidersEvaluated
RouteSelected
ProviderAttemptStarted(local)
Token*
ValidationCompleted(fail)
ProviderAttemptCompleted(failed)
FallbackStarted(reason = SchemaInvalid, next = cloud)
ProviderAttemptStarted(cloud)
Token*
ValidationCompleted(pass)
ProviderAttemptCompleted(success)
ArtifactStored?
Done
```

## Privacy rejection order

```text
Started
CacheChecked?
ProvidersEvaluated
RouteSelected?           // may include denied candidates
ProviderAttemptCompleted(rejected-by-policy)?
Failed(PolicyViolation.CloudNotAllowed)
```

A provider rejected by privacy policy must not receive `ProviderAttemptStarted` because it was not invoked.

## Timeout order

See `timeout-retry-policy.md` for timeout-specific variants.

## Retry events

Retry is not a separate stream event in MVP unless same-provider retry is enabled. When enabled, retries are represented as:

```text
ProviderAttemptCompleted(failed, retryable = true)
RetryScheduled(provider, delay)
ProviderAttemptStarted(provider, attemptNumber = n + 1)
```

If `RetryScheduled` is added to public API, update this source-of-truth document first.

## Monitor projection

`InferenceMonitor` receives redacted projections of these same events. It must not define a separate lifecycle. For example:

- `InferenceEvent.ProviderAttemptStarted` -> `MonitorEvent.ProviderAttemptStarted`
- `InferenceEvent.Token` -> `MonitorEvent.TokenEmitted` without token text by default
- `InferenceEvent.Done` -> `MonitorEvent.RequestCompleted`
- `InferenceEvent.Failed` -> `MonitorEvent.RequestFailed`

## Golden trace requirements

Every built-in policy should have golden traces for:

- success;
- unavailable fallback;
- validation fallback;
- timeout fallback;
- privacy rejection;
- cancellation.
