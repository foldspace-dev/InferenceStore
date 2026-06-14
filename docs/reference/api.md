# API reference

The complete, generated KDoc reference for every published module — types, functions,
and `explicitApi()` signatures, with source links back to GitHub.

[Browse the full API reference :octicons-arrow-right-24:](https://foldspace-dev.github.io/InferenceStore/api/){ .md-button .md-button--primary }

!!! note "Generated on deploy"
    The API reference is produced by [Dokka](https://kotlinlang.org/docs/dokka-introduction.html)
    from source on every docs deploy, so it always matches `main`. Locally, run
    `./gradlew dokkaGenerate` and open `build/dokka/html/index.html`.

## Core

<div class="grid cards" markdown>

- :material-cube-outline:{ .lg .middle } __inferencestore-core__

    ---

    The store, provider contract, policies, privacy, validation, events, caching,
    fingerprints, and the streaming engine.

    [:octicons-arrow-right-24: Browse](https://foldspace-dev.github.io/InferenceStore/api/inferencestore-core/index.html)

- :material-test-tube:{ .lg .middle } __inferencestore-testkit__

    ---

    Fake providers and route/event assertions for deterministic tests with no model or
    network.

    [:octicons-arrow-right-24: Browse](https://foldspace-dev.github.io/InferenceStore/api/inferencestore-testkit/index.html)

</div>

## Provider adapters

<div class="grid cards" markdown>

- :material-cloud-outline:{ .lg .middle } __openai-compatible__

    ---

    Cloud adapter for any OpenAI-compatible endpoint (Ktor, engine-agnostic).

    [:octicons-arrow-right-24: Browse](https://foldspace-dev.github.io/InferenceStore/api/inferencestore-provider-openai-compatible/index.html)

- :material-android:{ .lg .middle } __litertlm-android__

    ---

    On-device LiteRT-LM (Google AI Edge) adapter, runtime-injected.

    [:octicons-arrow-right-24: Browse](https://foldspace-dev.github.io/InferenceStore/api/inferencestore-provider-litertlm-android/index.html)

- :material-apple:{ .lg .middle } __apple-foundation__

    ---

    On-device Apple Foundation Models adapter (experimental), runtime-injected.

    [:octicons-arrow-right-24: Browse](https://foldspace-dev.github.io/InferenceStore/api/inferencestore-provider-apple-foundation/index.html)

- :material-firebase:{ .lg .middle } __firebase-android__

    ---

    Hybrid Firebase AI Logic adapter (cloud-like boundary).

    [:octicons-arrow-right-24: Browse](https://foldspace-dev.github.io/InferenceStore/api/inferencestore-provider-firebase-android/index.html)

</div>

## Storage & observability

<div class="grid cards" markdown>

- :material-database-outline:{ .lg .middle } __store-sqldelight__

    ---

    Durable, privacy-safe artifact persistence via SQLDelight.

    [:octicons-arrow-right-24: Browse](https://foldspace-dev.github.io/InferenceStore/api/inferencestore-store-sqldelight/index.html)

- :material-chart-timeline-variant:{ .lg .middle } __monitor-opentelemetry__

    ---

    Maps redacted monitor events to OpenTelemetry spans.

    [:octicons-arrow-right-24: Browse](https://foldspace-dev.github.io/InferenceStore/api/inferencestore-monitor-opentelemetry/index.html)

</div>
