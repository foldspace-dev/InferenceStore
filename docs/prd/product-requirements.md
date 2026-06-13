# Product Requirements Document: InferenceStore

Updated: 2026-06-13

## Product summary

InferenceStore is a Kotlin Multiplatform library that orchestrates inference across local and cloud providers using explicit, testable policy. It sits above on-device runtimes and cloud APIs, and owns routing, fallback, validation, caching hooks, deduplication, observability, and later background model lifecycle.

Tagline:

> local when possible, cloud when necessary, observable always.

## Corrections incorporated

This PRD has been reconciled with the RFCs, ADRs, and technical specs.

Decided items:

- **Core API shape:** request-first, `InferenceRequest<Output>`, not `InferenceStore<Key, Output>` generics at the root.
- **Key requirement:** `InferenceKey` is required for cache, dedupe, artifacts, and durable traces. Convenience APIs may generate ephemeral keys only when cache and dedupe are disabled.
- **Validation timing:** MVP validates final outputs only. Partial validation is post-MVP.
- **Privacy source of truth:** `docs/technical/privacy-model.md` owns classes, defaults, and enforcement rules.
- **Built-in policy count:** five MVP presets: `localOnly`, `cloudOnly`, `preferLocalThenCloud`, `preferCloudThenLocal`, `validateLocalThenCloudRepair`.
- **Event taxonomy:** `docs/technical/event-model.md` is canonical.
- **First real local adapter:** LiteRT-LM Android/JVM is in the MVP vertical slice.
- **Meeseeks milestone:** Meeseeks lifecycle is M5, not M4.
- **Validation gate:** M1 build work is gated by the 8/15 discovery signal in issue #037 unless manually waived and documented.

## Problem

AI-powered mobile apps increasingly need to combine local and cloud inference:

- Local inference is useful for privacy, offline use, latency, and marginal cost.
- Cloud inference is useful for quality, larger models, broad device coverage, multimodal support, long context, and tool calling.
- Device support and local model availability vary.
- Provider and runtime APIs differ across iOS, Android, JVM, JS, and backend.
- Feature teams reimplement routing, fallback, retry, validation, caching, privacy gates, and telemetry.
- Tests become brittle because real model calls are nondeterministic, expensive, and environment-dependent.

There is no broadly adopted KMP library that plays the role Store plays for data access, but for inference orchestration.

## Goals

### G1: One shared API

Developers can make inference requests from common KMP code without hard-coding provider selection into feature logic.

### G2: Explicit routing policies

Developers can express local/cloud behavior as policy using five MVP presets and later a DSL:

- `localOnly`
- `cloudOnly`
- `preferLocalThenCloud`
- `preferCloudThenLocal`
- `validateLocalThenCloudRepair`

Privacy is not a separate routing preset. Privacy is enforced by `PrivacyPolicy` before provider invocation.

### G3: Provider capability and availability model

Providers report availability, capabilities, model/runtime metadata, execution boundary, and stable error categories.

### G4: Streaming-first results

The API supports streaming events and non-streaming convenience methods.

### G5: Validation and repair

Final-output validators can trigger fallback or repair in MVP. Partial validation is post-MVP.

### G6: Route observability

Every request can emit route decisions, provider/model metadata, fallback reasons, timings, validator outcomes, timeout/retry decisions, and privacy denials in a redacted route trace.

### G7: Deterministic testkit

Developers can unit test route behavior, fallback behavior, streaming behavior, validation behavior, privacy behavior, timeout behavior, and cancellation behavior without real models.

### G8: Extensible adapters

Adapters can be added without changing the core API. The core does not become a model runtime.

### G9: Real local runtime proof in MVP

The MVP must include one real local adapter to exercise model availability, initialization, streaming, cancellation, timeout, and error mapping. The selected adapter is LiteRT-LM for Android/JVM.

## Non-goals

For MVP, InferenceStore will not:

- implement a new inference runtime;
- train, fine-tune, quantize, or compile models;
- manage model downloads in the core module;
- provide a hosted SaaS dashboard;
- guarantee output quality;
- provide universal semantic caching;
- support every modality;
- hide all provider capability differences;
- promise identical results across local and cloud models;
- implement iOS local adapter parity before the Android/JVM LiteRT-LM slice is validated.

## Personas

### KMP app engineer

Needs one API from shared code and wants platform-specific adapters hidden behind dependency injection.

### Mobile platform lead

Needs centralized AI policy, privacy boundaries, observability, and testing.

### Feature engineer

Needs simple calls, structured output, reliable fallback, and readable route traces without learning every provider API.

### Runtime maintainer

Needs a clean adapter contract so their runtime can be adopted by app teams.

## Use cases

### UC1: Private note summarization

A note-taking app summarizes notes locally when available. Cloud fallback is allowed only if the note is not marked private and the request privacy policy explicitly permits an approved cloud provider. The app records which path was used.

### UC2: Structured extraction

An app extracts tasks from a user message into JSON. Local output must satisfy schema. If final validation fails, the request falls back to a stronger cloud model when privacy allows.

### UC3: Offline assistant

An app provides a reduced-capability assistant when offline. The policy chooses local-only mode when network is unavailable.

### UC4: Cost-aware classification

Routine classification tasks use a small local model. Cloud is used only when local confidence is below threshold or the feature requires a capability unavailable locally.

### UC5: Enterprise policy

An enterprise app forbids cloud inference for certain privacy classes. The execution controller enforces this before any provider call, independent of routing policy bugs.

### UC6: Background precomputation

A read-it-later app schedules embeddings or summaries when the device is charging and on Wi-Fi, using Meeseeks in a post-MVP lifecycle module.

## Functional requirements

### FR1: Core request model

The library must define a common request model that supports:

- `InferenceKey`;
- text input;
- message input;
- optional attachments placeholder for post-MVP multimodal input;
- output type metadata;
- prompt/template version;
- `PrivacyPolicy` from the canonical privacy model;
- cache policy;
- route policy override;
- timeout policy;
- retry policy;
- metadata.

### FR2: Provider interface

The library must define a provider interface with:

- provider ID;
- provider kind: local, cloud, platform, remote, test;
- provider privacy boundary;
- availability check;
- capability check;
- streaming generation;
- optional token/cost estimates;
- model/runtime metadata;
- stable error mapping.

### FR3: Policy engine

The library must choose a route using:

- request policy;
- provider availability;
- provider capabilities;
- connectivity;
- privacy rules;
- cost budget;
- latency budget;
- timeout budget;
- quality/validation rules;
- previous failures if configured.

### FR4: Streaming event model

The library must emit the canonical lifecycle from `docs/technical/event-model.md`.

MVP public stream events:

- started;
- cache checked;
- providers evaluated;
- route selected;
- provider attempt started;
- token;
- partial;
- validation completed;
- provider attempt completed;
- fallback started;
- artifact stored;
- done;
- failed.

### FR5: Fallback

The library must support fallback according to `docs/technical/error-fallback-mapping.md`.

MVP fallback triggers:

- provider unavailable;
- unsupported capability;
- attempt timeout;
- transient error;
- rate limit when route policy allows;
- parser failure;
- validator failure;
- policy-defined failure.

Privacy violations are not a cloud fallback trigger; they are a gate that may reject individual providers before invocation.

### FR6: Validation

The library must support:

- predicate validator;
- JSON/schema validator design;
- validation result metadata;
- validation-triggered fallback/repair.

MVP validation timing is final-output only.

### FR7: Cache hooks

The library must expose interfaces for:

- in-memory output cache;
- persistent artifact store;
- prompt/request fingerprinting;
- cache validity checks;
- cache skip/refresh policy.

Persistence must require both cache-policy permission and privacy-policy permission.

### FR8: Dedupe

The library must coalesce concurrent equivalent requests when request, policy, output spec, and privacy settings allow sharing. MVP fan-out semantics are defined in `docs/technical/threading-dispatchers.md`.

### FR9: Observability

The library must expose redacted monitor hooks derived from the canonical event lifecycle.

### FR10: Testkit

The library must provide:

- fake providers;
- scripted provider responses;
- failure injection for every stable error category;
- virtual clock support;
- route assertion helpers;
- streaming assertion helpers;
- privacy denial assertions;
- timeout and cancellation assertions;
- dedupe fan-out assertions.

### FR11: MVP adapters

Initial adapters include:

- fake/test providers;
- OpenAI-compatible HTTP cloud adapter;
- LiteRT-LM Android/JVM local adapter.

Optional/post-MVP adapter candidates:

- Apple Foundation Models iOS;
- Firebase AI Logic Android/iOS;
- Cactus;
- Llamatik;
- MLC LLM;
- ExecuTorch-backed adapter.

### FR12: Meeseeks integration

M5 module should include background tasks for:

- model availability check;
- download orchestration hook;
- warmup;
- prune;
- telemetry upload;
- deferred retry.

## Non-functional requirements

### NFR1: KMP-first

Core must compile in commonMain. Platform-specific dependencies belong in adapter modules.

### NFR2: Coroutine/Flow-native

The core API should use Kotlin coroutines and Flow.

### NFR3: Main-safe streaming

`stream()` must be safe to call and collect from UI scopes if adapters obey the threading contract. Blocking local runtime work must run off the UI dispatcher.

### NFR4: Small core dependency graph

Core should depend on Kotlin stdlib, coroutines, serialization where necessary, and small internal utilities only.

### NFR5: Stable semantics

Route decisions, event sequences, timeout behavior, error mapping, privacy enforcement, and dedupe fan-out must be documented and testable.

### NFR6: Privacy-safe defaults

Default privacy is `Personal` with cloud denied, no prompt/output persistence, and metadata-only telemetry. Raw prompts and outputs must not be logged by default.

### NFR7: Cancellation-safe

Cancellation must propagate to provider calls where supported. Cancellation is terminal and must not trigger fallback.

### NFR8: Backpressure-aware

Streaming should respect Flow backpressure semantics.

### NFR9: Extensible error model

Provider errors must preserve raw cause internally while mapping to stable fallback categories.

## User experience

### Quickstart target

A developer should be able to write this in under 10 minutes with fake providers or an OpenAI-compatible provider:

```kotlin
val inferenceStore = InferenceStore.build {
    providers {
        register(FakeLocalProvider())
        register(OpenAICompatibleProvider(config))
    }
    policy = Policies.preferLocalThenCloud()
}

val summary = inferenceStore.generateText(
    key = InferenceKey("summary", note.id),
    input = note.body,
    privacy = PrivacyPolicy.personal(
        cloud = CloudPermission.ApprovedProviders(setOf(ProviderId("openai-compatible")))
    )
)
```

### Real-local demo target

A developer can swap the fake provider for LiteRT-LM when a local model path is available:

```kotlin
providers {
    register(
        LiteRtLmProvider(
            LiteRtLmProviderConfig(
                modelPath = System.getenv("INFERENCESTORE_LITERTLM_MODEL_PATH"),
                modelId = "gemma-local-demo"
            )
        )
    )
    register(OpenAICompatibleProvider(config))
}
```

## MVP acceptance criteria

1. M0 validation gate is recorded: at least 8 of 15 target interviews say they would try the library, or the maintainer documents a waiver.
2. Core compiles for JVM, Android, and iOS targets in CI.
3. Fake provider and testkit work.
4. OpenAI-compatible provider works.
5. LiteRT-LM Android/JVM local adapter works for text-generation streaming when a model path is supplied.
6. `preferLocalThenCloud` policy works with fake local and with LiteRT-LM local.
7. Streaming and non-streaming APIs work.
8. Fallback reason is captured and exposed.
9. Predicate validator works.
10. JSON/schema validator design is accepted, even if implementation is minimal.
11. Request dedupe semantics are implemented as defined in the threading contract.
12. Privacy model tests prove denied providers are not invoked.
13. Timeout/retry behavior follows the timeout contract.
14. Error-to-fallback mapping tests cover all stable categories.
15. Quickstart and sample app are documented.

## Out-of-scope until post-MVP

- semantic cache;
- model downloads;
- multimodal attachments;
- tool calling;
- prompt registry;
- remote policy control plane;
- enterprise compliance package;
- UI components;
- benchmarking dashboard;
- iOS local adapter parity.

## Dependencies

Potential dependencies:

- Kotlin coroutines;
- Kotlinx serialization;
- Ktor client for HTTP adapter;
- LiteRT-LM Android/JVM dependency in optional adapter module;
- SQLDelight for persistent artifact store, if implemented;
- Meeseeks for model lifecycle module;
- platform/runtime adapter dependencies as optional modules.

## Milestones

### M0: Validation

Docs, API sketch, static/scripted demo traces, competitive comparison, user interviews, and first adapter decision.

Exit gate: proceed to M1 only if 8 of 15 interviews say they would try the library, or if the maintainer explicitly records a waiver.

### M1: Core prototype

KMP core, fake providers, policy presets, streaming events, validators, testkit, privacy enforcement, timeout/error contracts, OpenAI-compatible adapter, and LiteRT-LM Android/JVM adapter.

### M2: Alpha

Docs quickstart, sample app, route monitor, in-memory cache, request dedupe, CI/publishing snapshots, security/privacy guide.

### M3: Mobile proof

Android + iOS sample, iOS local adapter decision/prototype, Firebase/Apple adapter exploration, adapter guide.

### M4: Production hardening

Artifact store, route journal, OpenTelemetry exporter, privacy recipes, cancellation hardening, binary compatibility setup.

### M5: Lifecycle

Meeseeks integration for downloads, warmup, pruning, provider inventory, deferred retries, and telemetry upload.

## Open questions

1. Which iOS local adapter should come first: Apple Foundation Models, LiteRT-LM Swift, Firebase AI Logic, or Cactus/Llamatik?
2. Should `ArtifactStore` ship in alpha or remain interface-only until production hardening?
3. Should token accounting live in core despite provider inconsistency?
4. How should provider capability versions be represented?
5. Should policy DSL ship before beta, or should presets and plain functions carry alpha?
6. Should storage adapters provide encryption recipes or first-party encryption hooks?
