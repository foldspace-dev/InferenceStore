# Testing strategy

Updated: 2026-06-13

## Goal

Make inference routing deterministic enough to unit test while still proving one real local adapter path.

Developers should not need real local models or cloud providers to test:

- route decisions;
- fallback;
- validation;
- cancellation;
- timeout/retry behavior;
- streaming event order;
- cache behavior;
- privacy enforcement;
- monitoring redaction;
- dedupe fan-out.

The project itself still needs optional real-adapter tests for LiteRT-LM and OpenAI-compatible providers.

## Testkit module

```text
inferencestore-testkit
```

## Fake provider

```kotlin
class FakeInferenceProvider(
    override val id: ProviderId,
    override val kind: ProviderKind = ProviderKind.Test,
    override val boundary: ProviderPrivacyBoundary = ProviderPrivacyBoundary.localTest(),
    private val script: ProviderScript
) : InferenceProvider
```

## Provider script

```kotlin
class ProviderScript {
    var availability: ProviderAvailability = Available
    val capabilities: MutableSet<Capability> = mutableSetOf()

    fun tokens(vararg tokens: String)
    fun complete(text: String)
    fun fail(error: ProviderError)
    fun delay(duration: Duration)
    fun blockUntilCancelled()
}
```

## Route assertions

```kotlin
assertRoute(result.trace) {
    attempted("local")
    fellBackTo("cloud", FallbackReason.ProviderUnavailable)
    completedWith("cloud")
}
```

## Event assertions

Event assertions must follow `docs/technical/event-model.md`.

```kotlin
assertEvents(events) {
    started()
    cacheChecked(CacheOutcome.Miss)
    providersEvaluated()
    routeSelected()
    providerAttemptStarted("local")
    token("hello")
    validationPassed()
    providerAttemptCompleted("local")
    done()
}
```

## Privacy assertions

```kotlin
assertRoute(trace) {
    didNotAttempt("cloud")
    rejected("cloud", PolicyViolation.CloudNotAllowed)
}
```

```kotlin
cloudProvider.assertInvocations(0)
```

## Cancellation tests

Provider scripts should be able to assert cancellation:

```kotlin
val provider = fakeProvider("slow") {
    delay(10.seconds)
    complete("done")
}

val job = launch { store.generate(request) }
advanceTimeBy(100.milliseconds)
job.cancel()

provider.assertCancelled()
```

Cancellation is terminal and should not fallback.

## Timeout tests

The testkit virtual clock must support:

- availability timeout;
- attempt timeout;
- request deadline;
- time-to-first-token timeout;
- idle stream timeout;
- validation timeout;
- retry backoff.

Example:

```kotlin
@Test
fun attemptTimeoutFallsBackToCloud() = runTest {
    val local = fakeProvider("local", ProviderKind.Local) {
        delay(10.seconds)
        complete("late")
    }
    val cloud = fakeProvider("cloud", ProviderKind.Cloud) {
        complete("fallback")
    }

    val result = store.generate(
        request.copy(timeout = TimeoutPolicy(attemptTimeout = 500.milliseconds))
    )

    assertRoute(result.trace) {
        failed("local", ErrorCategory.Timeout)
        fellBackTo("cloud", FallbackReason.Timeout)
        completedWith("cloud")
    }
}
```

## Validator tests

```kotlin
val validator = OutputValidator<String> { output, _ ->
    if (output.startsWith("{")) Pass else Fail("not json")
}

val result = store.generate(requestWithValidator(validator))

assertRoute(result.trace) {
    fellBackTo("cloud", FallbackReason.ValidatorRejected)
}
```

## Cache tests

- cache hit returns cached result;
- stale cache triggers provider call;
- privacy no-persist does not write;
- prompt version invalidates cache;
- output schema version invalidates cache;
- privacy class/policy version invalidates cache.

## Dedupe tests

MVP dedupe tests must match `threading-dispatchers.md`:

- two equivalent concurrent `generate()` calls produce one provider invocation;
- two equivalent `stream()` collectors before first token produce one provider invocation;
- late streaming collector after first token starts a new invocation or reads cache;
- non-equivalent requests do not dedupe;
- privacy-disabled dedupe does not share;
- cancellation of one joined collector does not cancel shared upstream while other collectors remain;
- cancellation of all collectors cancels upstream exactly once.

## Golden tests

For docs and sample policies, maintain golden route traces.

Example:

```json
{
  "policy": "preferLocalThenCloud",
  "attempts": [
    {"provider": "litertlm-local", "status": "failed", "reason": "SchemaInvalid"},
    {"provider": "openai-compatible", "status": "success"}
  ]
}
```

## Adapter tests

Each adapter must test:

- availability mapping;
- capability mapping;
- success;
- streaming;
- cancellation;
- timeout;
- rate limit;
- provider-specific error mapping;
- redaction;
- provider privacy boundary.

## LiteRT-LM adapter tests

LiteRT-LM-specific tests:

- missing model path -> `ProviderUnavailable`;
- unreadable model -> `ProviderUnavailable`;
- initialization timeout -> `Timeout`;
- initialization runs off main/test UI dispatcher;
- generation cancellation closes conversation;
- route trace includes model ID/backend/runtime metadata;
- no prompt/output in monitor events by default;
- optional real-model streaming test enabled by `INFERENCESTORE_LITERTLM_MODEL_PATH`.

## Integration tests

Use fake providers first. Real-provider tests should be optional and disabled by default.

Environment variables can enable them:

```text
INFERENCESTORE_OPENAI_COMPATIBLE_BASE_URL
INFERENCESTORE_OPENAI_COMPATIBLE_API_KEY
INFERENCESTORE_LITERTLM_MODEL_PATH
```

## CI target matrix

MVP:

- JVM;
- Android unit tests;
- iOS simulator compile;
- common tests;
- optional LiteRT-LM JVM real-model test when model path exists.

Post-MVP:

- iOS integration sample;
- JS if core supports it;
- binary compatibility validation;
- dokka docs.

## Testing principles

1. Route tests should not depend on model quality.
2. Privacy tests should prove providers were not invoked.
3. Adapter tests should map errors, not assert provider internals.
4. Streaming tests should assert canonical event order.
5. Policy tests should be readable enough to serve as documentation.
6. Real-runtime tests should validate lifecycle/failure behavior, not benchmark quality.

## Example test

```kotlin
@Test
fun localSchemaFailureFallsBackToCloudRepair() = runTest {
    val local = fakeProvider("local", ProviderKind.Local) {
        supports(Capability.TextGeneration, Capability.Streaming)
        complete("not json")
    }

    val cloud = fakeProvider("cloud", ProviderKind.Cloud) {
        supports(Capability.TextGeneration, Capability.StructuredOutput)
        complete("{\"summary\":\"hello\"}")
    }

    val store = testInferenceStore {
        provider(local)
        provider(cloud)
        policy(Policies.validateLocalThenCloudRepair())
    }

    val result = store.generate(summaryRequest)

    assertRoute(result.trace) {
        attempted("local")
        fellBackTo("cloud", FallbackReason.SchemaInvalid)
        completedWith("cloud")
    }
}
```
