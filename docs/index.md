# InferenceStore

**Store, but for inference** — a Kotlin Multiplatform library that gives application
teams a deterministic, testable, observable way to choose between local and cloud
inference.

> Call one inference API and get policy-driven routing, fallback, validation, caching,
> deduplication, observability, and background model lifecycle management — without
> hard-coding every model/runtime/provider decision into feature code.

## Start here

- [Quickstart](quickstart.md) — install to a first policy-routed request.
- Run the demo: `./gradlew :samples:notes-summary:run`.

## Concepts

- [Store](concepts/store.md) — the entry point.
- [Provider](concepts/provider.md) — a runtime/endpoint adapter.
- [Policy](concepts/policy.md) — routing across providers.
- [Validator](concepts/validator.md) — output checks and repair.
- [Artifact store](concepts/artifact-store.md) — caching and fingerprints.
- [Monitor](concepts/monitor.md) — redacted telemetry.

## Guides

- [Writing a provider adapter](guides/writing-a-provider-adapter.md)
- [Security & privacy](guides/security-and-privacy.md)

## Reference

The [`technical/`](technical/architecture.md) specs and [`rfcs/`](rfcs/RFC-0001-core-abstractions.md)
are the normative source; the pages above are the friendly entry points.

## Building these docs

```bash
pip install -r docs/requirements.txt
mkdocs serve   # http://127.0.0.1:8000
```
