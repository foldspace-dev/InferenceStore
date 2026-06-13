# Source notes

Generated: 2026-06-13

This project plan was grounded in the current public docs for Store, Meeseeks, and several hybrid/on-device inference projects.

## Store

Store is positioned as a Kotlin Multiplatform solution for working with data. Its docs describe Store as an architecture for coordinating network, memory cache, and local storage, with single-source-of-truth behavior, validation, request deduplication, offline-first data access, and fallbacks.

Relevant links:

- https://github.com/MobileNativeFoundation/Store
- https://store.mobilenativefoundation.org/docs/intro
- https://store.mobilenativefoundation.org/docs/concepts/store5/store
- https://store.mobilenativefoundation.org/docs/concepts/store5/fetcher
- https://store.mobilenativefoundation.org/docs/concepts/store5/source-of-truth
- https://store.mobilenativefoundation.org/docs/concepts/store5/validator
- https://store.mobilenativefoundation.org/docs/concepts/store5/bookkeeper

## Meeseeks

Meeseeks is a Kotlin Multiplatform library for scheduling and managing background tasks across Android, JVM, native iOS, and JS/Web. Its docs describe platform-backed scheduling through WorkManager, Quartz, BGTaskScheduler, and browser background sync APIs. It persists task metadata locally, supports retries/backoff, observes task state, and can replay missed terminal events.

Relevant links:

- https://github.com/matt-ramotar/meeseeks
- https://docs.meeseeks.mattramotar.dev/introduction
- https://docs.meeseeks.mattramotar.dev/quickstart
- https://klibs.io/package/dev.mattramotar.meeseeks/runtime/0.1.0

## Hybrid and on-device inference landscape

The ecosystem already contains runtime and vendor-specific hybrid paths. This increases the need for a stable orchestration layer while reducing the attractiveness of building a new runtime.

Relevant links:

- Firebase AI Logic Hybrid API for Android: https://firebase.google.com/docs/ai-logic/hybrid/android/get-started
- Android hybrid inference docs: https://developer.android.com/ai/hybrid
- Apple Foundation Models / Apple Intelligence guide: https://developer.apple.com/wwdc26/guides/apple-intelligence/
- LiteRT-LM: https://developers.google.com/edge/litert-lm/overview
- ExecuTorch: https://executorch.ai/
- MLC LLM: https://llm.mlc.ai/
- Cactus Kotlin: https://github.com/cactus-compute/cactus-kotlin
- Llamatik: https://github.com/ferranpons/llamatik
- Llamatik docs: https://docs.llamatik.com/

## Interpretation

The docs support three product conclusions:

1. Store's durable value is orchestration semantics, not caching alone.
2. Meeseeks can provide the background execution substrate for model lifecycle and deferred inference work.
3. The inference runtime layer is already crowded and volatile, so InferenceStore should be provider/runtime-neutral.
