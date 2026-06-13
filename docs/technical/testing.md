# Testing strategy

Generated: 2026-06-13

## Goal

Make inference routing deterministic enough to unit test.

Developers should not need real local models or cloud providers to test:

- route decisions
- fallback
- validation
- cancellation
- streaming event order
- cache behavior
- privacy enforcement
- monitoring redaction

## Testkit module

```text
inferencestore-testkit
```

## Fake provider

```kotlin
class FakeInferenceProvider(
    override val id: ProviderId,
    override val kind: ProviderKind = ProviderKind.Test,
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

```kotlin
assertEvents(events) {
    started()
    providerSelected("local")
    token("hello")
    validationPassed()
    done()
}
```

## Privacy assertions

```kotlin
assertRoute(trace) {
    didNotAttempt("cloud")
    failedBecause(PolicyViolation.CloudNotAllowed)
}
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

- cache hit returns cached result
- stale cache triggers provider call
- privacy no-persist does not write
- prompt version invalidates cache
- output schema version invalidates cache

## Dedupe tests

- two equivalent concurrent requests produce one provider invocation
- non-equivalent requests do not dedupe
- privacy-disabled dedupe does not share
- cancellation of one collector does not cancel shared upstream while other collectors remain

## Golden tests

For docs and sample policies, maintain golden route traces.

Example:

```json
{
  "policy": "preferLocalThenCloud",
  "attempts": [
    {"provider": "local", "status": "failed", "reason": "SchemaInvalid"},
    {"provider": "cloud", "status": "success"}
  ]
}
```

## Adapter tests

Each adapter must test:

- availability mapping
- capability mapping
- success
- streaming
- cancellation
- timeout
- rate limit
- provider-specific error mapping
- redaction

## Integration tests

Use fake providers first. Real-provider tests should be optional and disabled by default.

Environment variables can enable them:

```text
INFERENCESTORE_OPENAI_COMPATIBLE_BASE_URL
INFERENCESTORE_OPENAI_COMPATIBLE_API_KEY
```

## CI target matrix

MVP:

- JVM
- Android unit tests
- iOS simulator compile
- common tests

Post-MVP:

- iOS integration sample
- JS if core supports it
- binary compatibility validation
- dokka docs

## Testing principles

1. Route tests should not depend on model quality.
2. Privacy tests should prove providers were not invoked.
3. Adapter tests should map errors, not assert provider internals.
4. Streaming tests should assert event order.
5. Policy tests should be readable enough to serve as documentation.

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
        policy(Policies.validateLocalRepairWithCloud())
    }

    val result = store.generate(summaryRequest)

    assertRoute(result.trace) {
        attempted("local")
        fellBackTo("cloud", FallbackReason.SchemaInvalid)
        completedWith("cloud")
    }
}
```
