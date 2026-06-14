# InferenceStore: Store-like inference orchestration for Kotlin Multiplatform

Updated: 2026-06-13

This document set is an opinionated starting point for building **“Store, but for inference”**: a Kotlin Multiplatform library that gives application teams a deterministic, testable, observable way to choose between local and cloud inference.

The core thesis:

> App developers should call one inference API and get policy-driven routing, fallback, validation, caching, deduplication, observability, and background model lifecycle management — without hard-coding every model/runtime/provider decision into feature code.

## Quickstart

New here? Start with the [Quickstart](docs/quickstart.md) — install to a first
policy-routed request — and run the [`samples/notes-summary`](samples/notes-summary)
demo: `./gradlew :samples:notes-summary:run`.

Writing an adapter for a new runtime or endpoint? See
[Writing a provider adapter](docs/guides/writing-a-provider-adapter.md).

## What changed in this revision

This package now incorporates the planning critique:

- `docs/technical/privacy-model.md` is the single source of truth for privacy classes, cloud permission, persistence, telemetry, and provider boundaries.
- LiteRT-LM Android/JVM is pulled into the MVP as the first real local adapter.
- The PRD is reconciled with the RFCs/ADRs: request-first API, final-output validation in MVP, five built-in policy presets, canonical event model, and M5 Meeseeks lifecycle.
- Issue #037 wires the 8/15 validation gate into the backlog.
- The missing contracts are written: threading/dispatchers/dedupe, error-to-fallback mapping, and timeout/retry policy.
- `docs/REVISION_NOTES.md` summarizes the full patch set and backlog changes.

## Why this is not another local LLM wrapper

The runtime ecosystem is moving quickly. LiteRT-LM, ExecuTorch, MLC LLM, Llamatik, Cactus, Firebase AI Logic, Apple Foundation Models, and other SDKs already target model execution. InferenceStore should sit **above** them.

The initial library should own:

- **Policy**: local-only, cloud-only, prefer-local-then-cloud, prefer-cloud-then-local, validate-local-then-cloud-repair.
- **Privacy**: request-level privacy class, cloud permission, persistence permission, telemetry permission, and provider-boundary enforcement before invocation.
- **Provider abstraction**: runtime/cloud adapters with capability, availability, model metadata, and privacy boundary reporting.
- **Request orchestration**: streaming, retries, timeouts, cancellation, dedupe, fallback.
- **Validation and repair**: schema validation, task-specific validators, evaluator hooks, cloud repair paths.
- **Cache and source-of-truth semantics**: prompt/result fingerprints, model-version-aware artifacts, privacy-safe persistence.
- **Observability**: route decisions, fallback reasons, latency, token counts, estimated cost, validator outcomes.
- **Background lifecycle**: model download, warmup, pruning, telemetry upload, precomputation through Meeseeks after the core stabilizes.

## Repository layout

```text
docs/
  REVISION_NOTES.md
  strategy/
    strategy.md
    positioning.md
    validation-plan.md
    competitive-landscape.md
  prd/
    product-requirements.md
    mvp-scope.md
    metrics.md
  technical/
    architecture.md
    api-design.md
    event-model.md
    privacy-model.md
    routing-policy.md
    provider-adapters.md
    litert-lm-adapter.md
    threading-dispatchers.md
    error-fallback-mapping.md
    timeout-retry-policy.md
    caching-validation-dedupe.md
    observability-evals.md
    storage-model.md
    meeseeks-integration.md
    security-privacy.md
    testing.md
    release-plan.md
  rfcs/
    RFC-0001-core-abstractions.md
    RFC-0002-provider-adapter-model.md
    RFC-0003-policy-engine.md
    RFC-0004-cache-and-source-of-truth.md
    RFC-0005-meeseeks-model-lifecycle.md
  adr/
    0001-kmp-first.md
    0002-provider-adapters-not-runtime.md
    0003-streaming-first.md
    0004-privacy-policy-as-api.md
    0005-first-real-local-adapter-litertlm.md
  issues/
    README.md
    issues.csv
    issues.json
    001-*.md
templates/
  issue-template.md
  rfc-template.md
  adr-template.md
```

## Module layout (code)

The Gradle/KMP build (OSS-5) is organized as:

| Module | Targets | Purpose |
|---|---|---|
| `inferencestore-core` | common, JVM, Android, iOS | Request model, provider contract, streaming API, policy, privacy, events |
| `inferencestore-testkit` | common, JVM, Android, iOS | Fake providers + route assertions for deterministic tests |
| `inferencestore-provider-openai-compatible` | common, JVM, Android, iOS | OpenAI-compatible cloud adapter (Ktor) |
| `inferencestore-provider-litertlm-android` | JVM, Android | Placeholder for the first real local adapter (LiteRT-LM, OSS-29) |
| `samples/notes-summary` | JVM | Private note summarization sample |

Build with `./gradlew build`. The Android target is enabled automatically when an Android SDK is present (`ANDROID_HOME` / `local.properties`); CI builds the full matrix on macOS. Contributors without the Android SDK still build and test the common / JVM / iOS surface.

Version matrix: Kotlin 2.4.0 · Gradle 8.13 · AGP 8.13.2 · coroutines 1.11.0 · serialization 1.11.0 · Ktor 3.5.0.

## Suggested first public framing

**Working tagline**

> Offline-first AI architecture for Kotlin Multiplatform: local when possible, cloud when necessary, observable always.

**Working install shape**

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.mattramotar.inferencestore:core:<version>")
        }
        androidMain.dependencies {
            implementation("dev.mattramotar.inferencestore:provider-litertlm-android:<version>")
        }
    }
}
```

**Working usage shape**

```kotlin
val store = InferenceStore.build {
    providers {
        register(liteRtLmProvider)
        register(openAiCompatibleProvider)
    }

    policy = Policies.preferLocalThenCloud()

    cache(outputCache)
    monitor(myMonitor)
}

store.stream(
    InferenceRequest.text(
        key = InferenceKey("notes.summary", note.id),
        input = note.body,
        output = Summary.serializer(),
        privacy = PrivacyPolicy.personal(
            cloud = CloudPermission.ApprovedProviders(setOf(ProviderId("openai-compatible")))
        )
    )
).collect { event ->
    when (event) {
        is InferenceEvent.Token -> render(event.text)
        is InferenceEvent.Done -> save(event.result.output)
        is InferenceEvent.FallbackStarted -> log(event.reason)
        else -> Unit
    }
}
```

## Recommended next move

Start with a **thin vertical slice**:

1. Complete the M0 validation gate.
2. KMP `core`.
3. Fake provider and testkit.
4. OpenAI-compatible cloud adapter.
5. LiteRT-LM Android/JVM real local adapter.
6. Local-first/cloud-fallback policy.
7. Final-output schema validation.
8. Route telemetry.
9. One sample app: private note summarization.

Defer model download management, semantic cache, speculative execution, iOS adapter parity, and multi-runtime breadth until the core semantics feel right.

## References consulted

See `docs/00-source-notes.md` for source notes and links.
