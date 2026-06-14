# ADR-0005: Use LiteRT-LM as the first real local adapter

Date: 2026-06-13

## Status

Accepted. Implemented in OSS-29 as `inferencestore-provider-litertlm-android`
(runtime-injection pattern; see the [adapter plan](../technical/litert-lm-adapter.md)).

## Context

The original MVP relied on fake local providers and delayed real local runtime integration. That failed to test the core risk of the product: whether a Store-like orchestration layer actually handles local runtime realities such as model availability, initialization latency, resource cleanup, cancellation, memory pressure, and local-to-cloud fallback.

## Decision

The MVP includes a real LiteRT-LM Android/JVM adapter as the reference local runtime adapter.

Scope:

- Android/JVM text generation;
- explicit `.litertlm` model path;
- streaming output;
- model availability/error mapping;
- off-main initialization;
- no model download management;
- iOS/Swift adapter deferred to mobile proof.

## Rationale

LiteRT-LM gives the project a real local runtime without building a runtime. Its Kotlin API makes it suitable for the first adapter, and its cross-platform direction keeps the long-term path open. The adapter validates InferenceStore's actual differentiation: policy ownership, privacy gates, fallback, validation, route traces, timeouts, cancellation, and testability above runtimes.

## Alternatives considered (deferred candidates)

Other on-device runtimes were evaluated for the first real local adapter. All are
deferred rather than rejected — InferenceStore is a provider/orchestration layer
(ADR-0002), so additional runtimes can be added later as independent adapters behind
the same `InferenceProvider` contract. Only one was needed to validate the MVP.

| Candidate | Why deferred (not rejected) |
|---|---|
| **MediaPipe LLM Inference API** (Google AI Edge) | Higher-level wrapper over the same Google AI Edge stack LiteRT-LM targets directly; LiteRT-LM is the more direct, forward-looking runtime, so it was chosen first to avoid building on a layer that is itself converging on LiteRT. |
| **ExecuTorch** (PyTorch Edge) | Capable on-device runtime, but heavier native build/integration on both Android and iOS and no first-party Kotlin API; a strong later candidate once the adapter contract is proven. |
| **MLC LLM** (TVM-based) | Broad device/GPU coverage, but a heavier compilation toolchain and a less direct Kotlin surface than LiteRT-LM; revisit for hardware breadth. |
| **llama.cpp / GGUF** (e.g. llama.android, llama.rn) | Ubiquitous and well-understood, but C/C++ via JNI with no first-party Kotlin API; viable as a community-runtime adapter later. |
| **ONNX Runtime Mobile** | General-purpose inference runtime without LLM-first ergonomics (KV-cache, streaming token APIs); deferred in favor of an LLM-native runtime. |
| **Cactus** | Cross-platform on-device inference SDK, but it overlaps InferenceStore's own positioning; coupling the *reference* adapter to a competing-layer SDK would muddy the orchestration-vs-runtime story (see the competitive landscape doc). |
| **Apple Foundation Models** | iOS/macOS platform models only — not an Android/JVM option. The iOS local-adapter decision is tracked separately in ADR-0006 (→ OSS-33, prototype OSS-41). |

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Large `.litertlm` model files cannot be assumed in CI or by every contributor. | The adapter depends on an injected `LiteRtLmRuntime` interface, not the native library, so all availability/capability/error/streaming behavior is tested with a fake runtime; the real model is opt-in via a model path (OSS-29). |
| LiteRT-LM is pre-1.0 and its API may churn. | The native surface is isolated behind `LiteRtLmRuntime`; churn is contained to the integrator's implementation, not InferenceStore core or routing. |
| Native runtime thread-safety is unproven. | `maxConcurrentConversations` defaults to 1; concurrency is opt-in and observable. |
| Fine-grained init failures (timeout, OOM, runtime-init) collapse to `UnavailableReason.Unknown` in the shipped enum. | Acceptable for MVP routing (any `Unavailable` triggers fallback); the richer `LiteRtLmFailure` is preserved at the adapter boundary and the reason enum can be extended without a contract change. |
| Android/JVM leads iOS in the first vertical slice. | iOS local adapter is decided in ADR-0006 (Apple Foundation Models) so the gap is explicit and tracked, not open-ended. |

## Consequences

Positive:

- MVP exercises real local failure modes.
- OpenAI-compatible cloud fallback is no longer the only real adapter.
- Cactus/Firebase overlap is less concerning because the alpha demonstrates orchestration above an independent local runtime.

Negative:

- MVP setup becomes heavier for users who want the real-local demo.
- Model files are large and cannot be assumed in CI.
- Android/JVM will lead iOS in the first vertical slice.

## Follow-ups

- ✅ Implement `inferencestore-provider-litertlm-android` (OSS-29, runtime-injection).
- ✅ Keep fake providers in the testkit for deterministic policy tests (OSS-12).
- ✅ Add the iOS local adapter decision (ADR-0006 → Apple Foundation Models, OSS-33).
- ✅ Model download / warmup / telemetry are Meeseeks lifecycle workers, not adapter
  concerns (OSS-35 / OSS-38 / OSS-39).
- ⏳ JVM CLI/sample mode driven by a real model path remains an optional follow-up; the
  `samples/notes-summary` demo runs with fake providers by default.
