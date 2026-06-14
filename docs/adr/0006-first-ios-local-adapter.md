# ADR-0006: First iOS local/platform adapter

Status: Accepted
Updated: 2026-06-14

## Context

The MVP's first real local adapter is LiteRT-LM on Android/JVM (ADR-0005). iOS needs
its own deliberate choice now that the core semantics (provider contract, privacy
boundary, streaming, error→fallback) are settled. Candidates differ sharply on
platform reach, maturity, and how much model/runtime management the app must own.

Candidates: Apple Foundation Models, LiteRT-LM (Swift), Firebase AI Logic, Cactus,
Llamatik.

## Comparison

| Candidate | Platform support | Maturity | Streaming | Structured output | Model management | KMP/Swift interop |
| --- | --- | --- | --- | --- | --- | --- |
| **Apple Foundation Models** | iOS/iPadOS/macOS/visionOS 26+ on Apple-Intelligence-capable devices only | Vendor-supported, new API | Yes (token stream) | Yes (guided generation / `@Generable`) | None — OS-managed on-device model | Swift framework; bridge via a Swift shim → Kotlin/Native cinterop |
| **LiteRT-LM (Swift)** | iOS + Android (same engine as the Android adapter) | Early, Google AI Edge | Yes | Via prompt/JSON (no constrained decoding yet) | App downloads/manages `.task` models | C/Swift API; same `LiteRtLmRuntime` injection shape as Android |
| **Firebase AI Logic** | iOS/Android, cloud + limited hybrid | Vendor-supported | Yes | Gemini structured output | Cloud (or on-device where available) | Swift/Kotlin SDKs; cloud-leaning — not a pure local adapter |
| **Cactus** | iOS/Android, on-device (GGUF) | Community/early | Yes | Prompt-level | App manages GGUF models | C/Swift; needs a bridge |
| **Llamatik** | iOS (llama.cpp) | Community/early | Yes | Prompt-level | App manages GGUF models | C/Swift; needs a bridge |

## Decision

**Adopt Apple Foundation Models as the first iOS local/platform adapter**, mirroring the
Android choice (each platform's native, vendor-supported on-device stack):

- Its on-device `SystemLanguageModel` is the privacy-strongest option (no network, no
  app-managed model, no download), which is the headline use case. (The framework also
  exposes a server-based `PrivateCloudComputeLanguageModel`; if an app opts into that
  path it must be modeled as a separate cloud-like boundary, not as this local provider.)
- It supports streaming and structured output (guided generation), matching the core
  capabilities (`Streaming`, `StructuredOutput`).
- Zero model management removes the biggest adapter-authoring burden.
- It maps cleanly to `ProviderKind.Platform` with a `platform("apple")` privacy
  boundary, and reports honest availability (device/OS capability gating).

**Keep LiteRT-LM (Swift) as the documented second choice** for teams needing
cross-platform parity with Android or device coverage below the Apple Intelligence bar —
it reuses the same runtime-injection adapter shape as the Android module.

The adapter will follow the OSS-29 pattern: a `runtime` interface the integrator
implements against the native framework, so the adapter stays testable without the SDK
and the core API is unchanged.

## Risks and deferred candidates

- **Device coverage**: Foundation Models requires iOS/iPadOS/macOS/visionOS 26+ on
  Apple-Intelligence-capable hardware; `availability()` must report `Unavailable` and
  route to cloud on older/ineligible devices. The sample/tests must exercise the
  unavailable path.
- **API churn**: the framework is new; pin to a maturity level of *experimental* until
  validated on a device.
- **Interop cost**: bridging a Swift-only framework into Kotlin/Native needs a thin Swift
  shim; this is the main implementation risk.
- **Deferred**: Firebase AI Logic is tracked separately as a hybrid cloud adapter
  (OSS-32), not a local one. Cactus and Llamatik are deferred (community maturity,
  GGUF management overhead) and revisited if Foundation Models coverage proves too
  narrow.

## Consequences

A follow-up prototype issue is created for the Apple Foundation Models adapter
(`inferencestore-provider-apple-foundation`, runtime-injected, experimental). The core
API is unaffected; iOS gains a privacy-first local provider symmetric with Android's
LiteRT-LM.
