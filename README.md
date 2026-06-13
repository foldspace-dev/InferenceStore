# InferenceStore: Store-like inference orchestration for Kotlin Multiplatform

Generated: 2026-06-13

This document set is an opinionated starting point for building **“Store, but for inference”**: a Kotlin Multiplatform library that gives application teams a deterministic, testable, observable way to choose between local and cloud inference.

The core thesis:

> App developers should call one inference API and get policy-driven routing, fallback, validation, caching, deduplication, observability, and background model lifecycle management — without hard-coding every model/runtime/provider decision into feature code.

## Why this is not another local LLM wrapper

The runtime ecosystem is moving quickly. LiteRT-LM, ExecuTorch, MLC LLM, Llamatik, Cactus, Firebase AI Logic, Apple Foundation Models, and other SDKs already target model execution. InferenceStore should sit **above** them.

The initial library should own:

- **Policy**: local-first, cloud-first, local-only, cloud-only, privacy-first, cost-aware, latency-aware, quality-repair.
- **Provider abstraction**: runtime/cloud adapters with capability and availability reporting.
- **Request orchestration**: streaming, retries, timeouts, cancellation, dedupe, fallback.
- **Validation and repair**: schema validation, task-specific validators, evaluator hooks, cloud repair paths.
- **Cache and source-of-truth semantics**: prompt/result fingerprints, model-version-aware artifacts, privacy-safe persistence.
- **Observability**: route decisions, fallback reasons, latency, token counts, estimated cost, validator outcomes.
- **Background lifecycle**: model download, warmup, pruning, telemetry upload, precomputation through Meeseeks.

## Repository layout

```text
docs/
  strategy/
    strategy.md
    positioning.md
    validation-plan.md
  prd/
    product-requirements.md
    mvp-scope.md
    metrics.md
  technical/
    architecture.md
    api-design.md
    routing-policy.md
    provider-adapters.md
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
    }
}
```

**Working usage shape**

```kotlin
val store = InferenceStore.build {
    providers {
        register(localProvider)
        register(openAiCompatibleProvider)
    }

    policy = Policies.preferLocalThenCloud {
        requirePrivacyBoundary()
        maxLocalLatency(2.seconds)
        fallbackOn(OutputFailure.SchemaInvalid)
    }

    cache(outputCache)
    monitor(myMonitor)
}

store.stream(
    InferenceRequest.text(
        key = InferenceKey("notes.summary", note.id),
        input = note.body,
        output = Summary.serializer()
    )
).collect { event ->
    when (event) {
        is InferenceEvent.Token -> render(event.text)
        is InferenceEvent.Done -> save(event.output)
        is InferenceEvent.RouteChanged -> log(event.reason)
    }
}
```

## Recommended next move

Start with a **thin vertical slice**:

1. KMP `core`.
2. Fake provider and testkit.
3. OpenAI-compatible cloud adapter.
4. One local adapter behind an optional module.
5. Local-first/cloud-fallback policy.
6. Schema validation.
7. Route telemetry.
8. One sample app: private note summarization.

Defer model download management, semantic cache, speculative execution, and multi-runtime support until the core semantics feel right.

## References consulted

See `docs/00-source-notes.md` for source notes and links.
