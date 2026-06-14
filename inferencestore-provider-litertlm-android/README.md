# inferencestore-provider-litertlm-android

A local, on-device `InferenceProvider` backed by **LiteRT-LM** (Google AI Edge) —
the MVP's first real local adapter (`docs/technical/litert-lm-adapter.md`).

## Design: runtime injection

The adapter is **runtime-agnostic**. It depends on a `LiteRtLmRuntime` interface,
not on the native library directly, so the adapter's logic — availability/error
mapping, capability reporting, streaming-event mapping, and the local privacy
boundary — is real and fully unit-tested with a fake runtime, and the heavyweight
native binding is a clean integration point. Threading/off-dispatcher execution
is a documented **contract on the `LiteRtLmRuntime` implementation**, not enforced
or tested by the adapter.

```kotlin
val provider = LiteRtLmProvider(
    config = LiteRtLmProviderConfig(
        modelPath = "/path/to/model.litertlm",
        modelId = "gemma-2b-it",
        backend = LiteRtLmBackend.Auto,
    ),
    runtime = MyLiteRtLmRuntime(), // your LiteRtLmRuntime impl over Google AI Edge
)
```

## Providing the real runtime

Integrators implement `LiteRtLmRuntime` with the LiteRT-LM library:

- `probe(modelPath, backend)` — validate the `.litertlm` file exists/readable and
  the backend is supported (bounded; no full generation). Map to `LiteRtLmStatus`.
- `generate(modelPath, backend, prompt)` — a **cold** `Flow<String>` of tokens.
  Run engine init and synchronous native calls **off the UI dispatcher** (the
  blocking execution context), map runtime errors to `LiteRtLmException` with a
  stable `ErrorCategory`, and **close the conversation/engine on cancellation**.

The native binding is intentionally not bundled (it is not a Maven artifact and
needs a model file). This module ships at **experimental** maturity: the contract
and logic are complete and tested; wire a real `LiteRtLmRuntime` to run inference.

## Behavior

- Privacy boundary: `LocalProcess` (no cloud permission required).
- Capabilities: `TextGeneration`, `Chat`, `Streaming`, `Offline`.
- Missing/unreadable model → `ProviderUnavailable` (routing can fall back).
- Probe is bounded by `min(availabilityTimeout, initializationTimeout)`.
- Unmapped native failures during generation are terminal (`Unknown`).

## Sample / real-model test

`samples/notes-summary` runs with fakes by default; set
`INFERENCESTORE_LITERTLM_MODEL_PATH` to exercise a real model path against a real
`LiteRtLmRuntime` (OSS-31).
