# Enforce local-only privacy

**Goal:** keep a request on-device and guarantee it can never reach the cloud — even if a
cloud provider is registered and the local one is unavailable. Then prove it in a test.

## 1. Mark the request local-only

```kotlin
val request = InferenceRequest.text(
    key = InferenceKey("journal.summary", entry.id),
    input = entry.body,
    privacy = PrivacyPolicy.localOnly(),   // cloud denied, regardless of policy
)
```

`PrivacyPolicy.localOnly()` denies cloud unconditionally. `PrivacyPolicy.Default` (the
strict default — `Personal`, cloud denied) gives the same guarantee for the cloud gate.

## 2. The gate runs before any provider

The privacy gate executes **before** route planning, so a cloud-like provider is rejected
and never invoked — no prompt leaves the device. If the local provider can't serve, the
request *fails* rather than silently falling back to cloud:

```kotlin
val result = runCatching { store.generate(request) }
// If on-device is unavailable, this is a failure — not a cloud call.
```

The trace records the refusal for auditing:

```kotlin
// rejected cloud -> PolicyViolation   (never attempted)
```

## 3. Prove zero cloud calls in a test

The testkit's fake providers count invocations, so you can assert the cloud was never
touched:

```kotlin
@Test
fun localOnly_neverCallsCloud() = runTest {
    val local = fakeProvider("on-device", ProviderKind.Local) {
        availability = ProviderAvailability.Unavailable(UnavailableReason.ModelMissing)
    }
    val cloud = fakeProvider("cloud", ProviderKind.Cloud) { complete("should never run") }
    val store = InferenceStore.build {
        provider(local); provider(cloud)
        policy = Policies.preferLocalThenCloud()
    }

    val request = InferenceRequest.text(key, "secret", privacy = PrivacyPolicy.localOnly())

    assertFailsWith<InferenceException> { store.generate(request) }
    assertEquals(0, cloud.invocations)   // the gate refused cloud
}
```

This is the test that turns "we don't send your data to the cloud" from a claim into a
guarantee.

## See also

- [Privacy model](../../technical/privacy-model.md) — classes, cloud permission, and the gate
- [Security &amp; privacy](../security-and-privacy.md)
- [Add cloud fallback](cloud-fallback.md) — when cloud *is* allowed
