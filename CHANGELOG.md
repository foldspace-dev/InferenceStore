# Changelog

All notable changes to InferenceStore are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Pre-1.0 / `0.1.0-SNAPSHOT`. The MVP (M1) and alpha (M2) capabilities:

### Added

- **Streaming-first engine** — `InferenceStore.stream()` (cold `Flow`) and
  `generate()`, with request-carried output types.
- **Routing & fallback** — `InferencePolicy` with five built-in presets
  (`localOnly`, `cloudOnly`, `preferLocalThenCloud`, `preferCloudThenLocal`,
  `validateLocalThenCloudRepair`) and a canonical error→fallback mapping.
- **Privacy** — `PrivacyPolicy` (class, cloud permission, persistence, telemetry,
  redaction, provider boundary) enforced by an un-bypassable gate before any
  provider work.
- **Validation & repair** — `OutputValidator`s including `wellFormedJson()` /
  `validJson(serializer)`, with validation-triggered cloud repair.
- **Timeout / retry / backoff** — layered timeouts, opt-in same-provider retry,
  backoff policies.
- **Caching & dedupe** — `InferenceCache` / `InferenceArtifactStore` /
  `InferenceArtifact`, `MemoryInferenceCache` (TTL, stale-while-revalidate), request
  fingerprinting, and in-flight request deduplication.
- **Observability** — `InferenceMonitor` with a redacted `MonitorEvent` projection
  and a durable `RouteTrace`.
- **Providers** — OpenAI-compatible (engine-agnostic Ktor) and LiteRT-LM
  (runtime-injected, on-device) adapters; `inferencestore-testkit` with fake
  providers and route/event assertions.
- **Docs & sample** — Quickstart, provider-adapter and security/privacy guides, a
  MkDocs site, and the runnable `samples/notes-summary` demo.

[Unreleased]: https://github.com/foldspace-dev/InferenceStore/commits/main
