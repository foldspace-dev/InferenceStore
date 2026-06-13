# Error category to fallback mapping

Updated: 2026-06-13

This document defines how stable error categories map to retry, fallback, repair, or terminal failure. Policies may restrict fallback further, but they must not make the same category mean different default behavior.

## Stable error categories

```kotlin
sealed interface InferenceError {
    val category: ErrorCategory
    val providerId: ProviderId?
    val retryAfter: Duration?
    val cause: Throwable?
}

enum class ErrorCategory {
    ProviderUnavailable,
    CapabilityUnsupported,
    PolicyViolation,
    Timeout,
    RateLimited,
    TransientProviderError,
    PermanentProviderError,
    ValidationFailed,
    ParsingFailed,
    Cancelled,
    Unknown
}
```

Raw exceptions are preserved internally and may be available to debug hooks, but routing operates on stable categories.

## Default mapping table

| Error category | Same-provider retry default | Fallback default | Terminal default | Fallback reason | Notes |
|---|---:|---:|---:|---|---|
| `ProviderUnavailable` | No | Yes | No, if another allowed provider exists | `ProviderUnavailable` | Missing model, unsupported device, provider disabled. |
| `CapabilityUnsupported` | No | Yes | No, if another allowed provider exists | `CapabilityUnsupported` | Do not retry a provider that cannot support the request. |
| `PolicyViolation` | No | No for denied provider; route may try another provider if allowed | Yes when no allowed route exists | `PolicyViolation` | Privacy violations are enforced before invocation. |
| `Timeout` | No by default | Yes for attempt timeout | Yes for request deadline exhaustion | `Timeout` | See timeout contract. |
| `RateLimited` | Only if retry policy and `retryAfter` fit deadline | Yes to another provider | Yes if no route or deadline | `RateLimited` | Retry must be observable. |
| `TransientProviderError` | Optional, explicit retry only | Yes after retry budget or when retry disabled | No, if another allowed provider exists | `TransientError` | Network reset, temporary runtime failure. |
| `PermanentProviderError` | No | Yes only if provider-specific and request itself is valid | Yes for invalid request/config | `PermanentError` | Adapter must distinguish invalid request from provider-specific failure when possible. |
| `ValidationFailed` | No | Yes when policy includes validation fallback/repair | Yes otherwise | `ValidatorRejected` or `SchemaInvalid` | Final-output validation in MVP. |
| `ParsingFailed` | No | Yes when policy includes parser fallback/repair | Yes otherwise | `OutputParserFailed` | Includes typed output parser failure. |
| `Cancelled` | No | No | Yes | None | Caller cancellation is terminal. |
| `Unknown` | No | No by default | Yes | `Unknown` | Adapter should improve mapping before enabling fallback. |

## Error source matters

`Timeout` has two sources:

- `AttemptTimeout`: the active provider attempt exceeded its timeout. Fallback may run if the request deadline has enough remaining budget.
- `RequestDeadlineExceeded`: the whole request deadline expired. Fallback is terminal unless a policy explicitly allows returning a cached stale artifact.

`PermanentProviderError` also has two common sources:

- provider-specific permanent failure, such as model corrupt or local runtime initialization failure; fallback may be useful;
- request/config invalidity, such as malformed API key, invalid schema, or invalid prompt format; fallback is usually terminal.

Adapters should include `ErrorSource` metadata when possible.

## Fallback guardrails

Fallback is allowed only when all are true:

1. The mapping table allows fallback for the error category.
2. The route has another candidate.
3. The next provider passes privacy checks.
4. The next provider supports the request capabilities.
5. The request deadline has enough budget for another attempt.
6. Policy allows fallback for that category.
7. The request is safe to re-run.

## Retry guardrails

Same-provider retry is allowed only when all are true:

1. `RetryPolicy` explicitly allows retry for the category.
2. Attempt count is below the retry limit.
3. Retry delay fits within the request deadline.
4. The provider call is safe to repeat.
5. A retry event is emitted.

Core default: same-provider retries are disabled. Prefer fallback over hidden retries.

## Provider adapter mapping requirements

Adapters must map platform/runtime errors into these categories. Examples:

| Adapter observation | Category |
|---|---|
| Local model file missing | `ProviderUnavailable` |
| Local runtime unsupported device/backend | `ProviderUnavailable` or `CapabilityUnsupported` |
| Native OOM during model load | `ProviderUnavailable` if model cannot load; `TransientProviderError` if recoverable pressure signal exists |
| Cloud HTTP 401/403 | `PermanentProviderError` or `PolicyViolation` depending source |
| Cloud HTTP 408/504 | `Timeout` or `TransientProviderError` depending client timeout source |
| Cloud HTTP 429 | `RateLimited` |
| Cloud HTTP 5xx | `TransientProviderError` |
| JSON parse failure | `ParsingFailed` |
| Schema validator fail | `ValidationFailed` |
| Caller cancels coroutine | `Cancelled` |

## Testkit requirements

The testkit must support scripted failures for every category and route assertions for the default mapping table.

Example:

```kotlin
assertRoute(result.trace) {
    attempted("local")
    failed("local", ErrorCategory.ProviderUnavailable)
    fellBackTo("cloud", FallbackReason.ProviderUnavailable)
    completedWith("cloud")
}
```
