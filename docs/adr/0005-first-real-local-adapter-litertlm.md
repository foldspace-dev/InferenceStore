# ADR-0005: Use LiteRT-LM as the first real local adapter

Date: 2026-06-13

## Status

Accepted for MVP planning.

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

- Implement `inferencestore-provider-litertlm-android` and JVM sample mode.
- Keep fake providers in the testkit for deterministic policy tests.
- Add iOS local adapter decision after the MVP route semantics settle.
