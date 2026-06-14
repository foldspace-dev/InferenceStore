# Add cloud fallback

**Goal:** prefer an on-device model, and fall back to a cloud provider when the local
one is unavailable, times out, or returns invalid output.

## 1. Register both providers and a prefer-local policy

```kotlin
val store = InferenceStore.build {
    provider(onDevice)   // a Local/Platform provider (LiteRT-LM, Apple Foundation Models)
    provider(cloud)      // a Cloud provider (OpenAI-compatible, Firebase)
    policy = Policies.preferLocalThenCloud()
}
```

The policy puts on-device (`Local` and `Platform` kinds) ahead of cloud. The engine
probes each candidate's availability and capability, then runs the route with automatic
fallback.

## 2. Permit cloud for this request

Cloud is refused unless the request's privacy policy allows it. For non-sensitive data:

```kotlin
val request = InferenceRequest.text(
    key = InferenceKey("notes.summary", note.id),
    input = note.body,
    privacy = PrivacyPolicy.publicData(),   // cloud allowed
)

val result = store.generate(request)
println("served by ${result.trace?.finalProvider}")
```

To allow only specific cloud providers instead of all of them, use
`CloudPermission.ApprovedProviders`:

```kotlin
privacy = PrivacyPolicy(
    classification = PrivacyClass.Personal,
    cloud = CloudPermission.ApprovedProviders(setOf(ProviderId("openai"))),
)
```

## 3. Read the route trace

When the local model can't serve, the trace records the rejection and the hop:

```kotlin
result.trace?.let { trace ->
    println("final = ${trace.finalProvider}")            // "cloud"
    trace.rejectedProviders.forEach { println("rejected ${it.providerId} -> ${it.reason}") }
    trace.fallbackReasons.forEach(::println)
}
// e.g. rejected on-device -> ProviderUnavailable ; final = cloud
```

!!! tip "It's the same for streaming"
    `store.stream(request)` emits a `FallbackStarted` event before the cloud attempt, so a
    UI can show "switching to cloud…" mid-request.

## See also

- [Policy](../../concepts/policy.md) · [Routing policy spec](../../technical/routing-policy.md)
- [Enforce local-only privacy](local-only-privacy.md) — the opposite guarantee
- [Error → fallback mapping](../../technical/error-fallback-mapping.md)
