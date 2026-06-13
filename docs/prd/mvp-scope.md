# MVP scope

Generated: 2026-06-13

## MVP theme

A tiny but credible Store-like inference router:

> One shared KMP call, two scripted providers, local-first/cloud-fallback policy, streaming events, validation, route telemetry, deterministic tests.

## MVP deliverables

### 1. Core module

Module name:

```text
inferencestore-core
```

Targets:

- common
- JVM
- Android
- iOS simulator/device

Core types:

- `InferenceStore`
- `InferenceRequest`
- `InferenceKey`
- `InferenceProvider`
- `ProviderId`
- `ProviderKind`
- `ProviderAvailability`
- `Capability`
- `CapabilityReport`
- `InferencePolicy`
- `InferenceRoute`
- `InferenceEvent`
- `InferenceResult`
- `InferenceError`
- `FallbackReason`
- `OutputValidator`
- `InferenceMonitor`

### 2. Testkit module

Module name:

```text
inferencestore-testkit
```

Features:

- scripted providers
- deterministic streaming
- configurable availability
- configurable capabilities
- failure injection
- route assertion helpers
- virtual clock hooks

### 3. OpenAI-compatible adapter

Module name:

```text
inferencestore-provider-openai-compatible
```

Supports:

- chat/completions-like API
- streaming
- model metadata
- timeout/cancellation where possible
- redacted monitor events

This adapter can work with OpenAI-compatible backends, self-hosted gateways, or local servers that expose OpenAI-compatible APIs.

### 4. Sample app / sample module

A minimal sample:

```text
samples/notes-summary
```

Features:

- text summarization
- privacy toggle
- local fake provider
- cloud fake or OpenAI-compatible provider
- schema validation demo
- route trace screen/log

### 5. Documentation

Required docs:

- Quickstart
- Architecture
- API design
- Policies
- Provider adapter guide
- Validation/fallback guide
- Testing guide
- Security/privacy guide

## Explicitly excluded from MVP

- Real local runtime adapter unless it is extremely low-effort.
- Model download management.
- Semantic cache.
- Persistent artifact store implementation.
- Tool calling.
- Multimodal input.
- Embeddings.
- Remote config.
- Hosted dashboard.
- Benchmark harness.

## MVP success criteria

### Developer success

A developer can:

1. Configure two providers.
2. Write a local-first policy.
3. Stream a result.
4. Validate the final output.
5. Fall back to cloud when local fails.
6. See a route trace.
7. Unit test all of the above.

### Maintainer success

The core API reveals whether the architecture is right before adapter complexity takes over.

### Ecosystem success

At least one runtime maintainer can implement a provider adapter without needing core changes.

## MVP demo script

### Scenario A: local success

1. Request `summarize-note`.
2. Policy selects local fake provider.
3. Stream emits tokens.
4. Validator passes.
5. Result metadata says `source = local`.

### Scenario B: local unavailable

1. Same request.
2. Local provider returns unavailable.
3. Policy selects cloud provider.
4. Event emits `RouteChanged`.
5. Result metadata says fallback reason: `ProviderUnavailable`.

### Scenario C: local schema failure

1. Request structured JSON output.
2. Local provider streams malformed JSON.
3. Validator fails.
4. Policy falls back to cloud repair.
5. Cloud result validates.
6. Route trace includes both attempts.

### Scenario D: privacy prevents cloud

1. Request marked `PrivacyBoundary.LocalOnly`.
2. Local provider unavailable.
3. Policy refuses cloud.
4. Error is `PolicyViolation.CloudNotAllowed`.
5. Test asserts no cloud provider was invoked.

## MVP API bar

Good:

```kotlin
inferenceStore.stream(request).collect { event -> ... }
```

Good:

```kotlin
val result = inferenceStore.generate(request)
```

Good:

```kotlin
assertRoute(result) {
    attempted("local")
    fellBackTo("cloud", because = FallbackReason.SchemaInvalid)
}
```

Bad:

```kotlin
if (deviceHasModel()) {
    try {
       local.generate(...)
    } catch (...) {
       cloud.generate(...)
    }
}
```

The library should make the bad pattern unnecessary.

## MVP release readiness checklist

- [ ] API docs generated.
- [ ] All core types documented.
- [ ] Tests cover route decisions.
- [ ] Tests cover cancellation.
- [ ] Tests cover provider failures.
- [ ] Tests cover validator failures.
- [ ] Tests cover streaming event ordering.
- [ ] Quickstart is runnable.
- [ ] Sample compiles.
- [ ] CI publishes snapshot artifacts.
- [ ] README clearly says this is not an inference runtime.
