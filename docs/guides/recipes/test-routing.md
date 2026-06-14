# Test routing deterministically

**Goal:** test routing, fallback, and privacy with no model and no network — on every
platform — using the `inferencestore-testkit`.

## 1. Add the testkit

```kotlin
// build.gradle.kts
dependencies {
    testImplementation(libs.inferencestore.testkit)
}
```

## 2. Script fake providers

`fakeProvider` builds a deterministic provider from a small DSL — set its availability, or
script tokens and a completion:

```kotlin
val local = fakeProvider("on-device", ProviderKind.Local) {
    availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)
}
val cloud = fakeProvider("cloud", ProviderKind.Cloud) {
    tokens("Sum", "mary")
    complete("Summary")
}
```

## 3. Assert the route and the event stream

```kotlin
@Test
fun fallsBackToCloud_whenLocalUnavailable() = runTest {
    val store = InferenceStore.build {
        provider(local); provider(cloud)
        policy = Policies.preferLocalThenCloud()
    }

    val request = InferenceRequest.text(key, "hi", privacy = PrivacyPolicy.publicData())
    val events = store.stream(request).toList()
    val trace = (events.last() as InferenceEvent.Done).result.trace

    assertRoute(trace) {
        rejected("on-device", FallbackReason.ProviderUnavailable)
        completedWith("cloud")
    }
    assertEvents(events) {
        started()
        done()
        noMoreEvents()
    }
}
```

`assertRoute` reads the redacted `RouteTrace`; its DSL covers `attempted`, `didNotAttempt`,
`completedWith`, `failed`, `fellBackTo`, and `rejected`. `assertEvents` checks the ordered
stream (`started`, `token`, `fallbackStarted`, `done`, `failed`, `noMoreEvents`, …).

## 4. Prove a privacy guarantee

Fake providers count invocations, so you can assert a provider was never called:

```kotlin
assertEquals(0, cloud.invocations)   // e.g. under PrivacyPolicy.localOnly()
```

!!! tip "Virtual time"
    Timeout, retry, and cooldown tests run under `runTest` with `TestScope.testTimeSource`,
    so backoff and TTLs advance instantly without real delays.

## See also

- [Testing](../../technical/testing.md)
- [Enforce local-only privacy](local-only-privacy.md)
- [Event model](../../technical/event-model.md)
