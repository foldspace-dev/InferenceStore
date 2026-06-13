# Product Requirements Document: InferenceStore

Generated: 2026-06-13

## Product summary

InferenceStore is a Kotlin Multiplatform library that orchestrates inference across local and cloud providers using explicit policies. It provides a Store-like architecture for AI features: provider abstraction, routing, fallback, validation, caching hooks, deduplication, observability, and deterministic tests.

## Problem

AI-powered mobile apps increasingly need to combine local and cloud inference:

- Local inference is useful for privacy, offline use, latency, and cost.
- Cloud inference is useful for quality, larger models, broad device coverage, multimodal support, long context, and tool calling.
- Device support and local model availability vary.
- Provider and runtime APIs differ across iOS, Android, JVM, JS, and backend.
- Feature teams often reimplement the same routing, fallback, retry, validation, and telemetry logic.
- Tests become brittle because real model calls are nondeterministic and expensive.

There is no broadly adopted KMP library that plays the role Store plays for data access, but for inference orchestration.

## Goals

### G1: One shared API

Developers can make inference requests from common KMP code without hard-coding provider selection into feature logic.

### G2: Explicit routing policies

Developers can express local/cloud behavior as policy:

- local-only
- cloud-only
- prefer-local
- prefer-cloud
- local-first with cloud fallback
- cloud-first with local fallback
- privacy-sensitive local-only
- validate-local then repair-with-cloud

### G3: Provider capability and availability model

Providers report whether they are available and whether they support a request.

### G4: Streaming-first results

The API supports streaming tokens/events and non-streaming convenience methods.

### G5: Validation and repair

Structured output and custom validators can trigger fallback or repair.

### G6: Route observability

Every request can emit route decisions, provider/model metadata, fallback reasons, timings, and validator outcomes.

### G7: Deterministic testkit

Developers can unit test route behavior, fallback behavior, streaming behavior, and validator behavior without real models.

### G8: Extensible adapters

Adapters can be added without changing the core API.

## Non-goals

For MVP, InferenceStore will not:

- implement a new inference runtime
- train, fine-tune, quantize, or compile models
- manage model downloads in the core module
- provide a hosted SaaS dashboard
- guarantee output quality
- provide universal semantic caching
- support every modality
- hide all provider capability differences
- promise identical results across local and cloud models

## Personas

### KMP app engineer

Needs one API from shared code and wants platform-specific adapters hidden behind dependency injection.

### Mobile platform lead

Needs centralized AI policy, privacy boundaries, observability, and testing.

### Feature engineer

Needs simple calls, structured output, and reliable fallback without learning every provider API.

### Runtime maintainer

Needs a clean adapter contract so their runtime can be adopted by app teams.

## Use cases

### UC1: Private note summarization

A note-taking app summarizes notes locally when available. Cloud fallback is allowed only if the note is not marked private. The app records which path was used.

### UC2: Structured extraction

An app extracts tasks from a user message into JSON. Local model output must satisfy schema. If schema validation fails, the request falls back to a stronger cloud model.

### UC3: Offline assistant

An app provides a reduced-capability assistant when offline. The policy chooses local-only mode when network is unavailable.

### UC4: Cost-aware classification

Routine classification tasks use a small local model. Cloud is used only when local confidence is below threshold or the feature requires a capability unavailable locally.

### UC5: Enterprise policy

An enterprise app forbids cloud inference for some data classes. The policy engine enforces this before any provider call.

### UC6: Background precomputation

A read-it-later app schedules embeddings/summaries when the device is charging and on Wi-Fi, using Meeseeks.

## Functional requirements

### FR1: Core request model

The library must define a common request model that supports:

- text input
- message input
- optional attachments placeholder
- output type metadata
- prompt/template version
- privacy classification
- cache policy
- route policy override
- timeout
- metadata

### FR2: Provider interface

The library must define a provider interface with:

- provider ID
- provider kind: local, cloud, platform, remote, test
- availability check
- capability check
- streaming generation
- optional token/cost estimates
- model metadata

### FR3: Policy engine

The library must choose a route using:

- request policy
- provider availability
- provider capabilities
- connectivity
- privacy rules
- cost budget
- latency budget
- quality rules
- previous failures if configured

### FR4: Streaming event model

The library must emit:

- loading / route planning
- provider selected
- route changed
- token
- partial structured output
- validator result
- fallback
- done
- error

### FR5: Fallback

The library must support fallback on:

- provider unavailable
- unsupported capability
- timeout
- transient error
- rate limit
- validator failure
- policy-defined failure

### FR6: Validation

The library must support:

- predicate validator
- JSON/schema validator
- custom evaluator hook
- validation result metadata
- fallback/repair trigger

### FR7: Cache hooks

The library must expose interfaces for:

- in-memory output cache
- persistent artifact store
- prompt/request fingerprinting
- cache validity checks
- cache skip/refresh policy

### FR8: Dedupe

The library must coalesce concurrent equivalent requests when the request and policy allow sharing.

### FR9: Observability

The library must expose monitor hooks for:

- request started
- route evaluated
- provider selected
- token emitted
- fallback triggered
- validation completed
- request completed
- request failed

### FR10: Testkit

The library must provide:

- fake providers
- scripted provider responses
- failure injection
- virtual clock support
- route assertion helpers
- streaming assertion helpers

### FR11: Optional adapters

Initial adapters should include:

- OpenAI-compatible HTTP adapter
- fake local adapter
- one local runtime/platform adapter after validation

### FR12: Meeseeks integration

Post-MVP module should include background tasks for:

- model availability check
- download
- warmup
- prune
- telemetry upload
- deferred retry

## Non-functional requirements

### NFR1: KMP-first

Core must compile in commonMain. Platform-specific dependencies belong in adapter modules.

### NFR2: Coroutine/Flow-native

The core API should use Kotlin coroutines and Flow.

### NFR3: Small core dependency graph

Core should depend on Kotlin stdlib, coroutines, serialization where necessary, and small internal utilities only.

### NFR4: Stable semantics

Route decisions and event sequences must be documented and testable.

### NFR5: Privacy-safe defaults

Prompts and outputs must not be logged by default. Cache persistence must be opt-in for sensitive requests.

### NFR6: Cancellation-safe

Cancellation must propagate to provider calls where supported.

### NFR7: Backpressure-aware

Streaming should respect Flow backpressure semantics.

### NFR8: Extensible error model

Provider errors must preserve raw cause while mapping to stable fallback categories.

## User experience

### Quickstart target

A developer should be able to write this in under 10 minutes:

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
    input = note.body
)
```

### Advanced target

A platform team should be able to configure:

```kotlin
val policy = policy {
    require {
        privacyAllowsCloud()
    }

    route {
        prefer(Local) whenAll {
            capability(TextGeneration)
            availability(Available)
            estimatedLatencyBelow(2.seconds)
        }

        fallback(Cloud) whenAny {
            unsupported(Capability.StructuredOutput)
            validationFailed()
            timedOut()
        }
    }

    observe {
        redactPrompts()
        emitFallbackReasons()
        emitCostEstimate()
    }
}
```

## MVP acceptance criteria

1. Core compiles for JVM, Android, iOS targets in CI.
2. Fake provider and OpenAI-compatible provider work.
3. `preferLocalThenCloud` policy works.
4. Streaming and non-streaming APIs work.
5. Fallback reason is captured and exposed.
6. Predicate validator works.
7. JSON/schema validator design is accepted, even if implementation is minimal.
8. Request dedupe works for identical concurrent requests.
9. Testkit supports deterministic route assertions.
10. Quickstart and sample app are documented.

## Out-of-scope until post-MVP

- semantic cache
- model downloads
- multimodal attachments
- tool calling
- prompt registry
- remote policy control plane
- enterprise compliance package
- UI components
- benchmarking dashboard

## Dependencies

Potential dependencies:

- Kotlin coroutines
- Kotlinx serialization
- Ktor client for HTTP adapter
- SQLDelight for persistent artifact store, if implemented
- Meeseeks for model lifecycle module
- Platform/runtime adapter dependencies as optional modules

## Milestones

### M0: Validation

Docs, API sketch, test fake, user interviews.

### M1: Core alpha

KMP core, fake providers, OpenAI-compatible adapter, simple policy, streaming events, validators, testkit.

### M2: Mobile proof

Android + iOS sample, one local/platform adapter, route telemetry.

### M3: Production hardening

Dedupe, cache hooks, artifact store, observability exporters, release automation.

### M4: Lifecycle

Meeseeks integration for downloads, warmup, pruning, and telemetry.

## Open questions

1. Should the public API use `InferenceStore<Key, Output>` generics like Store, or a more dynamic `InferenceRequest<Output>` shape?
2. Should validation operate on streamed partials or only final outputs in MVP?
3. How strongly should policy DSL be typed in v0?
4. Which local adapter should come first?
5. Should `ArtifactStore` be in MVP or RFC-only?
6. Should the library expose token accounting in core despite provider inconsistency?
7. How should provider capability versions be represented?
8. What privacy classes are built-in vs user-defined?
