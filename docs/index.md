---
hide:
  - navigation
  - toc
---

<div class="hero" markdown>
<div markdown>
<span class="hero__eyebrow">Kotlin Multiplatform · Android · iOS · JVM</span>

# Store, but for <span class="gradient">inference</span>

<p class="hero__sub">
One inference API for your app. InferenceStore gives you policy-driven routing,
privacy enforcement, fallback, validation, caching, deduplication, and observability
across local and cloud models — so feature code never hard-codes a model, runtime, or
provider.
</p>

<div class="hero__cta" markdown>
[Get started](quickstart.md){ .md-button .md-button--primary }
[Why InferenceStore](#why-inferencestore){ .md-button }
</div>
</div>

<div class="hero__code" markdown>
```kotlin
// Register your providers once — on-device and cloud.
val store = InferenceStore.build {
    provider(onDevice)               // LiteRT-LM, Apple Foundation Models, …
    provider(cloud)                  // any OpenAI-compatible endpoint
    policy = Policies.preferLocalThenCloud()
}

// One call: local when possible, cloud when needed —
// privacy enforced, output validated, every hop traced.
val result = store.generate(
    InferenceRequest.text(
        key = InferenceKey("notes.summary", note.id),
        input = note.body,
        privacy = PrivacyPolicy.publicData(),
        validator = OutputValidators.nonBlankText,
    ),
)

println(result.output)
println(result.trace?.finalProvider)   // "on-device" or "cloud"
```
</div>
</div>

## Why InferenceStore

The runtime ecosystem already executes models — LiteRT-LM, ExecuTorch, MLC, Apple
Foundation Models, Firebase AI Logic, OpenAI-compatible endpoints. InferenceStore sits
**above** them and owns the decisions your feature code shouldn't:

<div class="grid cards" markdown>

- :material-routes:{ .lg .middle } __Policy-driven routing__

    ---

    Five built-in presets — local-only, cloud-only, prefer-local, prefer-cloud, and
    validate-local-then-cloud-repair — or write your own. Routing is data, not
    `if`-statements scattered across features.

    [:octicons-arrow-right-24: Policy](concepts/policy.md)

- :material-shield-lock:{ .lg .middle } __Privacy enforced before invocation__

    ---

    A request's privacy class and cloud permission are checked **before** any provider
    runs. A local-only request can never reach the network — and a test can prove zero
    cloud calls.

    [:octicons-arrow-right-24: Privacy model](technical/privacy-model.md)

- :material-backup-restore:{ .lg .middle } __Fallback, validation & repair__

    ---

    When a provider is unavailable, times out, or returns invalid output, routing falls
    back to the next candidate — optionally repairing schema-invalid local output in the
    cloud.

    [:octicons-arrow-right-24: Validator](concepts/validator.md)

- :material-map-search:{ .lg .middle } __Every decision is traced__

    ---

    Each result and failure carries a redacted `RouteTrace`: what ran, what was rejected
    and why, fallback reasons, and timings — never raw prompts or outputs.

    [:octicons-arrow-right-24: Observability](technical/observability-evals.md)

- :material-database-arrow-down:{ .lg .middle } __Caching, dedupe & source of truth__

    ---

    Content-free fingerprints drive a privacy-safe cache and in-flight deduplication, with
    pluggable persistence (SQLDelight) for durable artifacts.

    [:octicons-arrow-right-24: Artifact store](concepts/artifact-store.md)

- :material-test-tube:{ .lg .middle } __Deterministic by design__

    ---

    A testkit of fake providers and route assertions lets you test routing, privacy, and
    fallback with no model and no network — on every platform.

    [:octicons-arrow-right-24: Testing](technical/testing.md)

</div>

## Start here

<div class="grid cards" markdown>

- :material-rocket-launch:{ .lg .middle } __Quickstart__

    ---

    Install to a first policy-routed, privacy-checked request in a few minutes.

    [:octicons-arrow-right-24: Quickstart](quickstart.md)

- :material-lightbulb-on:{ .lg .middle } __Concepts__

    ---

    The mental model: stores, providers, policies, privacy, validation, caching, and
    background lifecycle.

    [:octicons-arrow-right-24: Core concepts](concepts/store.md)

- :material-book-open-variant:{ .lg .middle } __Guides__

    ---

    Task-oriented walkthroughs: write a provider adapter, enforce privacy, validate and
    repair output.

    [:octicons-arrow-right-24: Guides](guides/writing-a-provider-adapter.md)

- :material-file-tree:{ .lg .middle } __Reference__

    ---

    The normative specs: event model, routing, privacy, storage, threading, and the API
    reference.

    [:octicons-arrow-right-24: Reference](technical/architecture.md)

</div>

## What you can build

- **Privacy-first summarization** — keep personal notes on-device, fall back to cloud
  only for explicitly public content, and prove it with a route trace.
- **Resilient extraction** — validate structured output against a schema locally, and
  repair in the cloud when the small model gets it wrong.
- **Offline-first assistants** — prefer the on-device model, degrade gracefully to cloud
  when it's unavailable, and warm the model in the background before it's needed.

---

InferenceStore is open source under Apache-2.0. Targets: Android, iOS, and JVM, with
`explicitApi()` and a public-first API. Pre-release (`0.1.0-SNAPSHOT`).
[Star it on GitHub :octicons-star-fill-16:](https://github.com/foldspace-dev/InferenceStore)
