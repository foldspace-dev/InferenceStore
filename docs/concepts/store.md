# Store

`InferenceStore` is the single entry point. You build one from a set of providers and
a policy, then make requests against it — the store handles routing, fallback,
validation, caching, dedupe, and observability so feature code doesn't have to.

```kotlin
val store = InferenceStore.build {
    provider(onDevice)
    provider(cloud)
    policy = Policies.preferLocalThenCloud()
}
```

Two ways to make a request:

- `stream(request)` — a **cold** `Flow<InferenceEvent>` (tokens, validation, fallback,
  completion). Nothing runs until you collect; safe to collect from a UI scope.
- `generate(request)` — suspends and returns the terminal `InferenceResult` (or throws
  `InferenceException`).

`InferenceStore.single(provider)` wraps one provider with no fallback. The store is
`AutoCloseable` — `close()` releases its dedupe scope.

Learn more: [Quickstart](../quickstart.md), [architecture](../technical/architecture.md),
[event model](../technical/event-model.md).
