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
    data class RetryScheduled(val requestId: RequestId, val provider: ProviderId, val attemptNumber: Int, val delay: Duration) : InferenceEvent<Nothing>
    data class ArtifactStored(val requestId: RequestId, val outcome: ArtifactWriteOutcome) : InferenceEvent<Nothing>
    data class Done<Output : Any>(val requestId: RequestId, val result: InferenceResult<Output>) : InferenceEvent<Output>
    data class Failed(val requestId: RequestId, val error: InferenceError, val trace: RouteTrace) : InferenceEvent<Nothing>
}
```

## Shared event vocabulary

`FallbackReason`, `AttemptOutcome`, and the provider-attempt summary are defined here and referenced — not redefined — by `error-fallback-mapping.md`, `timeout-retry-policy.md`, and `observability-evals.md`.

```kotlin
enum class FallbackReason {
    ProviderUnavailable,
    CapabilityUnsupported,
    PolicyViolation,
    Timeout,
    RateLimited,
    TransientError,
    PermanentError,
    ValidatorRejected,
    SchemaInvalid,
    OutputParserFailed,
    Unknown
}

enum class AttemptOutcome { Succeeded, Failed, RejectedByPolicy }

data class ProviderAttemptSummary(
    val provider: ProviderId,
    val attemptNumber: Int,
    val outcome: AttemptOutcome,
    val error: ErrorCategory? = null,   // present when outcome = Failed
    val retryable: Boolean = false,
    val retryAfter: Duration? = null
)
```

`ErrorCategory` is the stable taxonomy defined in `error-fallback-mapping.md`. A failed attempt is always carried by `ProviderAttemptCompleted` with `outcome = Failed`; there is no separate `ProviderAttemptFailed` event.

## Cancellation representation

Caller cancellation terminates the cancelling collector's `Flow` with `CancellationException`; core does not deliver a terminal `Failed` event to that collector, because Kotlin Flow does not permit emission after cancellation. The route trace and `InferenceMonitor` still record `Cancelled` when observable. For deduped streams, cancelling one joined collector does not cancel upstream while another remains (see `threading-dispatchers.md`).

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

Same-provider retry is an MVP capability but is disabled by default (`RetryPolicy.maxRetriesPerAttempt = 0`; prefer fallback over hidden retries — see `timeout-retry-policy.md`). When retry is enabled for an error category, each retry is an explicit, observable event:

```text
ProviderAttemptCompleted(outcome = Failed, retryable = true)
RetryScheduled(provider, attemptNumber = n + 1, delay)
ProviderAttemptStarted(provider, attemptNumber = n + 1)
```

`RetryScheduled` is part of the public `InferenceEvent` API (above) and is projected to a redacted `MonitorEvent.RetryScheduled`.

## Monitor projection

`InferenceMonitor` receives redacted projections of these same stream events and must not define a separate lifecycle. The `MonitorEvent` types are defined in `observability-evals.md`. Every stream event maps as follows:

| Stream `InferenceEvent` | `MonitorEvent` | Notes |
|---|---|---|
| `Started` | `RequestStarted` | |
| `CacheChecked` | `CacheChecked` | |
| `ProvidersEvaluated` | `ProvidersEvaluated` | |
| `RouteSelected` | `RouteSelected` | |
| `ProviderAttemptStarted` | `ProviderAttemptStarted` | |
| `Token` | `TokenEmitted` | count only, no token text by default |
| `Partial` | — | not projected; typed content is redacted |
| `ValidationCompleted` | `ValidationCompleted` | |
| `ProviderAttemptCompleted` | `ProviderAttemptCompleted` | carries `AttemptOutcome` |
| `FallbackStarted` | `FallbackStarted` | |
| `RetryScheduled` | `RetryScheduled` | |
| `ArtifactStored` | — | not a distinct monitor event; captured in `RouteTrace` and `RequestCompleted` |
| `Done` | `RequestCompleted` | |
| `Failed` | `RequestFailed` | includes `Cancelled` when observable |

## Golden trace requirements

Every built-in policy should have golden traces for:

- success;
- unavailable fallback;
- validation fallback;
- timeout fallback;
- privacy rejection;
- cancellation.
