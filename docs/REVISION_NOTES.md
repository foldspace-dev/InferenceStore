# Revision notes

Updated: 2026-06-13

This revision addresses the critique that the original planning package had strong architectural instincts but inconsistent security semantics, stale PRD/RFC decisions, and an MVP that mocked away the riskiest part of the product.

## Completed changes

### 1. Privacy model normalized

Created `docs/technical/privacy-model.md` as the single source of truth.

Canonical model:

- `PrivacyPolicy` owns privacy class, cloud permission, persistence permission, telemetry permission, redaction, and provider boundary checks.
- Privacy classes are `Public`, `Internal`, `Personal`, `Sensitive`, `LocalOnly`, and `Custom(value)`.
- Cloud permission is not a privacy class. It is represented by `CloudPermission`.
- Persistence is globally opt-in. A cache policy alone is insufficient to persist prompts or outputs.
- The execution controller enforces privacy before provider invocation, independent of routing policy.

Updated dependent docs: PRD, MVP scope, architecture, API design, routing policy, provider adapters, cache/storage, observability, security/privacy, testing, RFCs, ADRs, and issues.

### 2. Real local adapter pulled into MVP

Created `docs/technical/litert-lm-adapter.md` and `docs/adr/0005-first-real-local-adapter-litertlm.md`.

MVP now includes:

- `inferencestore-provider-litertlm-android`
- `inferencestore-provider-litertlm-jvm` where practical for sample/CLI validation
- real local text-generation streaming
- model-path configuration
- LiteRT-LM availability/capability/error mapping
- no model download management in the adapter

The deterministic fake provider remains required for testkit and validation demos, but the MVP is no longer fake-only.

### 3. PRD reconciled with RFCs/ADRs

Updated the PRD and dependent specs so the following decisions are explicit:

- root `InferenceStore` is not generic; output type travels on `InferenceRequest<Output>`;
- `InferenceKey` is required for cache, dedupe, artifacts, and durable traces;
- MVP validates final outputs only;
- partial streamed validation is post-MVP;
- there are exactly five MVP policy presets;
- the event model is canonical in `docs/technical/event-model.md`;
- Meeseeks lifecycle work is M5, not M4;
- LiteRT-LM Android/JVM is the first real local adapter.

### 4. Validation gate wired into backlog

Added issue #037: `Run validation interviews and record MVP gate decision`.

M1 build work now references the M0 gate. The rule is:

- run 15 target interviews;
- proceed if at least 8 say they would try the library and at least 5 concrete near-term use cases are captured;
- otherwise record a maintainer waiver before M1 build continues.

Updated validation plan, PRD, MVP scope, release plan, issue dependencies, and backlog summaries.

### 5. Missing technical contracts written

Created or rewrote:

- `docs/technical/threading-dispatchers.md`
- `docs/technical/error-fallback-mapping.md`
- `docs/technical/timeout-retry-policy.md`
- `docs/technical/event-model.md`

These documents define main-safety, dispatcher neutrality, local/native blocking rules, cancellation, dedupe fan-out, stable error categories, retry/fallback defaults, timeout layers, budget accounting, and canonical route events.

## Backlog changes

Issue count increased from 36 to 40.

New issues:

- #037 `Run validation interviews and record MVP gate decision`
- #038 `Implement LiteRT-LM Android/JVM local adapter`
- #039 `Implement threading, cancellation, and dedupe fan-out contract`
- #040 `Implement error category to fallback mapping contract`

Major moved/rewritten issues:

- #006 now defines exactly five built-in policies.
- #007 now implements the canonical privacy model.
- #015 OpenAI-compatible adapter is M1 because it is needed for the MVP cloud side.
- #021 timeout/retry is M1 P0, not a later stub.
- #022 is now the accepted LiteRT-LM adapter decision.
- #023 is Firebase AI Logic Android adapter, deferred to M3.
- #024 is iOS local/platform adapter decision/prototype, deferred to M3.
- #028–#030 are M5 Meeseeks lifecycle issues.
- #034 is now a static/scripted M0 validation demo, not dependent on M1 implementation work.

## Current milestone shape

- M0 Validation: demand validation, adapter decision, validation demo, public RFC discussion.
- M1 Core prototype: core API, provider contract, policy, privacy, validation, events, timeouts, fake/testkit, OpenAI-compatible adapter, LiteRT-LM Android/JVM adapter.
- M2 Alpha: cache, dedupe, monitor hooks, sample, docs, publishing.
- M3 Mobile proof: Firebase/Android hybrid exploration, iOS adapter decision/prototype, cross-platform sample.
- M4 Production hardening: persistent storage, route journal, telemetry exporter, security docs.
- M5 Meeseeks lifecycle: background inventory refresh, warmup, telemetry upload.

## Files most worth reviewing first

1. `docs/technical/privacy-model.md`
2. `docs/prd/mvp-scope.md`
3. `docs/technical/litert-lm-adapter.md`
4. `docs/technical/threading-dispatchers.md`
5. `docs/technical/error-fallback-mapping.md`
6. `docs/technical/timeout-retry-policy.md`
7. `docs/issues/backlog-summary.md`
