# Timeout, retry, and backoff policy

Updated: 2026-06-13

Timeouts and retries are part of the public route contract. They must be observable, testable, and consistent across providers.

## Timeout layers

```kotlin
data class TimeoutPolicy(
    val requestTimeout: Duration? = null,
    val availabilityTimeout: Duration? = 500.milliseconds,
    val attemptTimeout: Duration? = null,
    val timeToFirstTokenTimeout: Duration? = null,
    val idleStreamTimeout: Duration? = null,
    val validationTimeout: Duration? = null
)
```

### Request timeout

Maximum wall-clock budget for the full request: cache read, planning, availability probes, attempts, retries, fallback, validation, and persistence.

If the request timeout expires, the result is terminal `RequestDeadlineExceeded`. Core does not start a new fallback attempt after this deadline.

### Availability timeout

Maximum time spent probing a provider before treating availability as unknown/unavailable for this route plan.

MVP default: 500 ms per provider probe unless provider documentation requires a different default.

### Attempt timeout

Maximum time for a single provider attempt. Attempt timeout may trigger fallback if the request deadline has enough time remaining.

### Time-to-first-token timeout

Maximum time from provider attempt start until first content token/partial.

Useful for local model warmup or cloud tail latency.

### Idle stream timeout

Maximum gap between content events after streaming begins. This is not the same as total attempt timeout.

### Validation timeout

Maximum time for inline validators. Expensive evaluators are post-MVP and should not silently extend foreground request latency.

## Retry policy

```kotlin
data class RetryPolicy(
    val maxRetriesPerAttempt: Int = 0,
    val retryableCategories: Set<ErrorCategory> = emptySet(),
    val backoff: BackoffPolicy = BackoffPolicy.None,
    val respectRetryAfter: Boolean = true
)
```

Core default: zero same-provider retries.

Rationale: hidden retries can obscure fallback behavior, increase latency, and spend cloud cost without route visibility. When retry is enabled, each retry is an explicit event and trace entry.

## Backoff policy

```kotlin
sealed interface BackoffPolicy {
    data object None : BackoffPolicy
    data class Fixed(val delay: Duration) : BackoffPolicy
    data class Exponential(
        val initial: Duration,
        val multiplier: Double = 2.0,
        val maxDelay: Duration,
        val jitter: Jitter = Jitter.Full
    ) : BackoffPolicy
}
```

Backoff must use the testkit virtual clock in tests. Do not sleep real time in unit tests.

## Event sequence

Event names and `FallbackReason` are canonical in `event-model.md`. A failed attempt is `ProviderAttemptCompleted(outcome = Failed)` — there is no `ProviderAttemptFailed` event.

Attempt timeout with fallback:

```text
Started
RouteSelected
ProviderAttemptStarted(local)
ProviderAttemptCompleted(local, outcome = Failed, error = Timeout/AttemptTimeout)
FallbackStarted(reason = Timeout, next = cloud)
ProviderAttemptStarted(cloud)
ValidationCompleted(pass)
ProviderAttemptCompleted(cloud, outcome = Succeeded)
Done
```

Rate limit with explicit retry (retry is opt-in; disabled by default):

```text
ProviderAttemptStarted(cloud, attemptNumber = 1)
ProviderAttemptCompleted(cloud, outcome = Failed, error = RateLimited, retryable = true, retryAfter = 2s)
RetryScheduled(cloud, attemptNumber = 2, delay = 2s)
ProviderAttemptStarted(cloud, attemptNumber = 2)
...
```

Request deadline exhausted:

```text
ProviderAttemptStarted(local)
ProviderAttemptCompleted(local, outcome = Failed, error = Timeout/RequestDeadlineExceeded)
Failed(RequestDeadlineExceeded)
```

## Budget accounting

Before starting retry or fallback, core must check remaining request budget.

```kotlin
if (!deadline.hasBudgetFor(nextAttempt.minimumExpectedDuration)) {
    fail(RequestDeadlineExceeded)
}
```

In MVP, `minimumExpectedDuration` may be zero or provider supplied. Post-MVP can use observed latency distributions.

## Provider responsibilities

Adapters must:

- honor coroutine cancellation where possible;
- expose client-level timeout configuration when available;
- avoid unbounded blocking native calls on UI dispatchers;
- map provider timeout exceptions to `Timeout` with source metadata;
- not implement hidden retries unless unavoidable in the underlying SDK;
- document any underlying SDK retry behavior.

## OpenAI-compatible adapter default

- request timeout: inherited from request or client config;
- attempt timeout: request override or provider config;
- retry: disabled by default;
- rate-limit retry: opt-in only;
- API key/config errors: terminal permanent errors.

## LiteRT-LM adapter default

- model initialization timeout: adapter config, default 15 seconds for demos;
- time-to-first-token timeout: request or adapter config;
- idle stream timeout: optional;
- retry: disabled;
- fallback on missing/corrupt model or init timeout if policy and privacy allow cloud.

## Test requirements

- attempt timeout falls back;
- request timeout is terminal;
- rate limit with retry is observable;
- retry delay uses virtual clock;
- caller cancellation is not timeout;
- provider cleanup runs on timeout and cancellation;
- fallback is skipped when remaining budget is exhausted.
