# MVP scope

Updated: 2026-06-13

## MVP theme

A tiny but credible Store-like inference router:

> One shared KMP call, a real local LiteRT-LM provider, a cloud provider, local-first/cloud-fallback policy, validation, route telemetry, privacy enforcement, deterministic tests.

The MVP must not be fake-only. Fake providers remain essential for deterministic testing, but the vertical slice includes one real local runtime adapter so the architecture is tested against initialization latency, model availability, native resource cleanup, cancellation, and local-runtime errors.

## Pre-MVP validation gate

Before M1 build starts, complete issue #037:

- interview 15 target developers;
- record whether at least 8 would try the project;
- publish a short go/no-go note;
- document a maintainer waiver if building proceeds without passing the gate.

## MVP deliverables

### 1. Core module

Module name:

```text
inferencestore-core
```

Targets:

- common;
- JVM;
- Android;
- iOS simulator/device compile.

Core types:

- `InferenceStore`;
- `InferenceRequest<Output>`;
- `InferenceKey`;
- `InferenceProvider`;
- `ProviderId`;
- `ProviderKind`;
- `ProviderPrivacyBoundary`;
- `ProviderAvailability`;
- `Capability`;
- `CapabilityReport`;
- `InferencePolicy`;
- `InferenceRoute`;
- `InferenceEvent` from the canonical event model;
- `InferenceResult`;
- `InferenceError`;
- `FallbackReason`;
- `TimeoutPolicy`;
- `RetryPolicy`;
- `OutputValidator`;
- `InferenceMonitor`;
- `PrivacyPolicy` from the canonical privacy model.

### 2. Testkit module

Module name:

```text
inferencestore-testkit
```

Features:

- scripted providers;
- deterministic streaming;
- configurable availability;
- configurable capabilities;
- failure injection for every stable error category;
- route assertion helpers;
- event assertion helpers;
- privacy assertion helpers;
- timeout/cancellation helpers;
- virtual clock hooks;
- dedupe fan-out assertions.

### 3. OpenAI-compatible adapter

Module name:

```text
inferencestore-provider-openai-compatible
```

Supports:

- chat/completions-like API;
- streaming;
- model metadata;
- timeout/cancellation where possible;
- redacted monitor events;
- stable error mapping;
- app-supplied API-key configuration.

This adapter can work with OpenAI-compatible backends, self-hosted gateways, or local servers that expose OpenAI-compatible APIs. The adapter is cloud/remote by default unless explicitly configured as an app-local server boundary.

### 4. LiteRT-LM local adapter

Module name:

```text
inferencestore-provider-litertlm-android
inferencestore-provider-litertlm-jvm
```

MVP support:

- explicit `.litertlm` model path;
- text generation;
- streaming through Kotlin `Flow` where available;
- model metadata;
- availability checks for missing/unreadable model path;
- off-main initialization;
- timeout/cancellation cleanup;
- local privacy boundary;
- error mapping to stable categories.

Not MVP:

- model download management;
- iOS Swift adapter;
- multimodal input;
- tools;
- schema-constrained decoding.

### 5. Sample app / sample module

Module name:

```text
samples/notes-summary
```

Features:

- text summarization;
- privacy toggle;
- fake local provider mode for deterministic docs;
- LiteRT-LM local provider mode when `INFERENCESTORE_LITERTLM_MODEL_PATH` is supplied;
- OpenAI-compatible cloud provider mode;
- final-output validation demo;
- route trace screen/log;
- local-only privacy denial demo.

### 6. Documentation

Required docs:

- Quickstart;
- Architecture;
- API design;
- Policies;
- Provider adapter guide;
- LiteRT-LM adapter guide;
- Validation/fallback guide;
- Testing guide;
- Security/privacy guide;
- Threading/dispatcher contract;
- Error/fallback mapping;
- Timeout/retry policy.

## Explicitly excluded from MVP

- Model download management.
- Semantic cache.
- Persistent artifact store implementation.
- Tool calling.
- Multimodal input.
- Embeddings.
- Remote config.
- Hosted dashboard.
- Benchmark harness.
- iOS local adapter parity.

## MVP success criteria

### Developer success

A developer can:

1. Configure two providers.
2. Configure a real LiteRT-LM local provider when a model exists.
3. Write a local-first policy.
4. Stream a result.
5. Validate the final output.
6. Fall back to cloud when local is unavailable, times out, or fails validation.
7. Prove cloud is not called for local-only data.
8. See a route trace.
9. Unit test all of the above.

### Maintainer success

The core API reveals whether the architecture is right before multi-runtime breadth takes over, while still exercising one real local runtime.

### Ecosystem success

At least one runtime maintainer can implement or critique a provider adapter without needing core changes.

## MVP demo script

### Scenario A: LiteRT-LM local success

1. Request `summarize-note`.
2. Policy selects LiteRT-LM local provider.
3. Engine initializes off main.
4. Stream emits tokens.
5. Validator passes.
6. Result metadata says `source = local`, `provider = litertlm-local`.

### Scenario B: local unavailable

1. Same request.
2. LiteRT-LM provider reports missing model path.
3. Policy selects cloud provider if privacy allows.
4. Event emits `FallbackStarted(reason = ProviderUnavailable)`.
5. Result metadata says fallback reason: `ProviderUnavailable`.

### Scenario C: local schema failure

1. Request structured JSON output.
2. Local provider streams malformed JSON or final parser fails.
3. Validator/parser fails.
4. Policy falls back to cloud repair provider.
5. Cloud output validates.
6. Trace includes both attempts.

### Scenario D: local-only privacy denial

1. Request is marked `PrivacyClass.LocalOnly`.
2. Local provider unavailable.
3. Cloud provider is rejected by privacy gate before invocation.
4. Result fails with `PolicyViolation.CloudNotAllowed`.
5. Test proves cloud provider invocation count is zero.

### Scenario E: local initialization timeout

1. LiteRT-LM initialization exceeds attempt/init timeout.
2. Attempt is cancelled and resources are cleaned up.
3. Policy falls back if privacy and deadline allow.
4. Trace records timeout source.

## MVP non-demo tests

- caller cancellation is terminal and does not fallback;
- request deadline exhaustion is terminal;
- attempt timeout can fallback;
- rate-limit fallback works for cloud;
- dedupe join-before-token shares upstream;
- late streaming collector starts new upstream or reads cache;
- `generate()` joins in-flight result;
- raw prompt/output are absent from monitor events by default.
