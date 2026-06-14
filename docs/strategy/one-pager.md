# One-pager: InferenceStore

Updated: 2026-06-13

## Working name

**InferenceStore**

## Problem

Mobile AI features now need both local and cloud inference. Local models provide privacy, offline functionality, low marginal cost, and low latency when available. Cloud models provide better quality, larger context, broad device coverage, and advanced capabilities.

Today, app teams often handle this in feature code:

```text
check device -> call local model -> catch errors -> maybe call cloud -> validate maybe -> log maybe -> cache maybe
```

That logic becomes duplicated, hard to test, hard to observe, and risky for privacy.

## Solution

InferenceStore is a Kotlin Multiplatform inference orchestration layer.

It gives teams one shared API for inference requests while centralizing:

- provider capability and availability checks
- local/cloud routing policy
- privacy guardrails
- fallback and repair
- output validation
- request dedupe
- cache/artifact hooks
- route telemetry
- deterministic tests
- background lifecycle via Meeseeks

## Product thesis

The durable value is not running a model. The durable value is deciding **which** model/provider should run for a given request under explicit app policy, then making that decision observable and testable.

## Why you

You maintain Store, which gives this project the right mental model for source-of-truth, validation, cache, fallback, and request dedupe. You also maintain Meeseeks, which gives it a natural KMP background execution story for model download, warmup, pruning, telemetry, and deferred inference.

## MVP

The M0 validation demo should prove four flows with scripted/fake traces, and the MVP implementation should repeat them with one real local adapter where possible:

1. Local provider succeeds.
2. Local unavailable falls back to cloud.
3. Local output fails validation and cloud repairs it.
4. Privacy policy blocks cloud fallback.

No real model runtime is required for the first validation demo. The MVP implementation must include LiteRT-LM Android/JVM so local-runtime behavior is exercised before the architecture locks.

## Initial modules

```text
inferencestore-core
inferencestore-testkit
inferencestore-provider-openai-compatible
inferencestore-provider-litertlm-android
inferencestore-provider-litertlm-jvm
samples/notes-summary
```

## Later modules

```text
inferencestore-provider-firebase-android
inferencestore-provider-apple-foundation
inferencestore-provider-llamatik
inferencestore-store-sqldelight
inferencestore-meeseeks
```

## Positioning

> Offline-first AI architecture for Kotlin Multiplatform: local when possible, cloud when necessary, observable always.

## Success signal

Proceed if developers say:

- “I need this because I do not want Firebase/Gemini lock-in.”
- “I need consistent behavior across iOS and Android.”
- “I need privacy/local-first, but cloud fallback for quality.”
- “I need to test and observe route decisions.”

Stop or narrow if feedback is mostly:

- “I only need a llama.cpp wrapper.”
- “I only target iOS.”
- “Firebase AI Logic is enough.”
- “I do not care about policy, observability, or tests.”
